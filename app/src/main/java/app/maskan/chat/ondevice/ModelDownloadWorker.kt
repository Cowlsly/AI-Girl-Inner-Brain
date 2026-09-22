package app.maskan.chat.ondevice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.maskan.chat.MaskanApplication
import app.maskan.chat.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.ResponseBody

/**
 * Downloads one model file, resumably, and refuses to install one whose checksum is wrong.
 *
 * The shape of it, and why each piece is there:
 *
 *  - **Onto a `.part`, renamed only after the sha256 matches.** A file under the real name is
 *    therefore always a file that has been verified, which is the invariant the engine relies on
 *    when it hands a path to a native runtime. A mismatch deletes the `.part` and says so by
 *    name; nothing is loaded.
 *  - **HTTP Range.** 1.6 GB over a phone connection does not finish in one go. The worker sends
 *    `Range: bytes=<what we already have>-` and appends. A server that ignores the range (200
 *    instead of 206) is handled by starting the file again rather than appending to a prefix
 *    that is now duplicated - silently corrupting the file would be caught by the checksum, but
 *    only after another gigabyte of someone's data.
 *  - **A foreground service with a progress notification**, so the download survives the user
 *    leaving the app. Its own channel, separate from renders: someone who has turned render
 *    notifications off has not asked to be kept in the dark about a gigabyte in flight.
 *
 * The network constraint (unmetered or not) is set by whoever enqueues this - see
 * ModelDownloadManager - because "download on mobile data" is the user's choice to make and
 * must not be a default hidden in here.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MaskanApplication
        val model = inputData.getString(KEY_MODEL_ID)?.let { ModelCatalog.byId(it) }
            ?: return@withContext Result.failure(reason(REASON_UNKNOWN_MODEL))
        val url = model.url ?: return@withContext Result.failure(reason(REASON_UNKNOWN_MODEL))
        val store = ModelStore(app)

        if (store.isInstalled(model)) return@withContext Result.success()
        if (!store.hasRoomFor(model)) return@withContext Result.failure(reason(REASON_NO_SPACE))

        val part = store.partFor(model)
        setForegroundSafely(model, 0, model.bytes, false)

        try {
            var have = if (part.isFile) part.length() else 0L
            // A .part longer than the finished file is a .part from another version of this
            // asset. Start again rather than trying to reason about it.
            if (have > model.bytes) {
                part.delete()
                have = 0L
            }
            if (have < model.bytes) {
                have = fetch(app, url, part, have, model)
            }
            if (have != model.bytes) return@withContext Result.retry()

            // Verify BEFORE the rename, always, including on a resumed download - a file
            // assembled from two connections is exactly the file most worth checking.
            setForegroundSafely(model, model.bytes, model.bytes, true)
            val actual = store.sha256(part) { done ->
                // Cheap, and it keeps the notification honest during the ten seconds the hash
                // takes on 1.6 GB, which otherwise looks like a hang at 100%.
                if (done % (128L * 1024 * 1024) < (1 shl 20)) {
                    setProgressBlocking(model.bytes, model.bytes, true)
                }
            }
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                part.delete()
                return@withContext Result.failure(reason(REASON_CHECKSUM))
            }

            val dest = store.fileFor(model)
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) return@withContext Result.failure(reason(REASON_RENAME))
            Result.success()
        } catch (e: PermanentHttpException) {
            // The server answered, and what it said was "no". Retrying cannot change that, and
            // a download that retries forever shows as "waiting for the network" - which blames
            // the user's connection for a file that is not there.
            Result.failure(
                workDataOf(
                    KEY_REASON to REASON_HTTP,
                    KEY_HTTP_CODE to e.code
                )
            )
        } catch (e: IOException) {
            // A dropped connection IS worth retrying: the bytes on disk are kept and the next
            // attempt resumes from them. Only a checksum mismatch throws work away.
            Result.retry()
        }
    }

    /** Append to [part] from [have], returning the new length. */
    private suspend fun fetch(
        app: MaskanApplication,
        url: String,
        part: File,
        have: Long,
        model: OnDeviceModel
    ): Long {
        val request = Request.Builder()
            .url(url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()

        // The shared client's read timeout is tuned for API calls. A download needs a long one
        // and no call timeout at all.
        val client = app.sharedOkHttpClient.newBuilder()
            .readTimeout(java.time.Duration.ofSeconds(60))
            .callTimeout(java.time.Duration.ZERO)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 408 and 429 are the two 4xx that genuinely mean "ask again later"; every
                // other 4xx is the server saying the request itself is wrong, and no amount of
                // waiting fixes a 404. 5xx falls through to the retrying branch.
                if (response.code in 400..499 &&
                    response.code != 408 && response.code != 429
                ) {
                    throw PermanentHttpException(response.code)
                }
                throw IOException("HTTP " + response.code)
            }
            // 206 means the server honoured the range and we append. 200 means it did not, and
            // the body is the WHOLE file - appending it to what we have would produce a file of
            // the right length made of the wrong bytes for the first half.
            val append = response.code == 206 && have > 0
            val body: ResponseBody = response.body ?: throw IOException("empty body")
            var written = if (append) have else 0L
            java.io.FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(1 shl 16)
                body.byteStream().use { input ->
                    while (true) {
                        // WorkManager's own stop signal: cancelled by the user, or a
                        // constraint lost (they left Wi-Fi). Either way the bytes on disk are
                        // kept and the next attempt resumes from them.
                        if (isStopped) throw IOException("stopped")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written % (4L * 1024 * 1024) < buffer.size) {
                            setProgressBlocking(written, model.bytes, false)
                            setForegroundSafely(model, written, model.bytes, false)
                        }
                    }
                }
                out.fd.sync()
            }
            return written
        }
    }

    private fun setProgressBlocking(done: Long, total: Long, verifying: Boolean) {
        setProgressAsync(
            workDataOf(
                KEY_DONE to done,
                KEY_TOTAL to total,
                KEY_VERIFYING to verifying
            )
        )
    }

    private fun reason(code: String) = workDataOf(KEY_REASON to code)

    // ── Notification ───────────────────────────────────────────────────────

    private suspend fun setForegroundSafely(
        model: OnDeviceModel,
        done: Long,
        total: Long,
        verifying: Boolean
    ) {
        // If the OS refuses the foreground service - app deep in the background on Android 12+ -
        // the download still runs for as long as WorkManager allows and resumes afterwards from
        // the .part. Never let that surface as a crash.
        runCatching { setForeground(foregroundInfo(model, done, total, verifying)) }
    }

    private fun foregroundInfo(
        model: OnDeviceModel,
        done: Long,
        total: Long,
        verifying: Boolean
    ): ForegroundInfo {
        ensureChannel()
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val text = if (verifying) {
            applicationContext.getString(R.string.ondevice_verifying)
        } else {
            applicationContext.getString(
                R.string.ondevice_downloading_progress,
                Formats.mb(applicationContext, done),
                Formats.mb(applicationContext, total)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                applicationContext.getString(R.string.ondevice_download_title, model.displayName)
            )
            .setContentText(text)
            .setProgress(100, percent, verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Re-created every time on purpose, like the render channel: it updates the name to the
        // app's current language and leaves the user's own importance choice alone.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.ondevice_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    companion object {
        const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 0x4D4F44

        const val KEY_MODEL_ID = "modelId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_REASON = "reason"

        /** An HTTP status that will not change by being asked again. See the catch above. */
        private class PermanentHttpException(val code: Int) : IOException("HTTP " + code)

        const val KEY_HTTP_CODE = "httpCode"

        const val REASON_HTTP = "http"
        const val REASON_CHECKSUM = "checksum"
        const val REASON_NO_SPACE = "space"
        const val REASON_RENAME = "rename"
        const val REASON_UNKNOWN_MODEL = "model"
    }
}
