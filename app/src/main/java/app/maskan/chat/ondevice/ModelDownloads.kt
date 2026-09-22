package app.maskan.chat.ondevice

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.maskan.chat.R
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Sizes, written the way a person reads them.
 *
 * One place, because the same number appears on the download card, in the notification and on
 * the delete button, and the delete button's number is a promise - "1.5 GB comes back" has to
 * be the same 1.5 GB the card asked for.
 */
object Formats {

    /** e.g. "1.6 GB", "687 MB". Decimal units, because that is what storage is sold in. */
    fun bytes(context: Context, value: Long): String {
        val gb = 1_000_000_000.0
        val mb = 1_000_000.0
        return if (value >= gb) {
            context.getString(
                R.string.unit_gb,
                String.format(Locale.US, "%.1f", value / gb)
            )
        } else {
            context.getString(R.string.unit_mb, (value / mb).toInt().toString())
        }
    }

    /** The same, for the progress line where both halves should use one unit. */
    fun mb(context: Context, value: Long): String = bytes(context, value)
}

/** What the screen needs to know about a download in flight. */
sealed class DownloadState {
    data object Idle : DownloadState()
    /**
     * Enqueued and not yet running.
     *
     * [retrying] separates "has never run, so it is waiting for the network it was told to wait
     * for" from "ran, failed on something transient, and is backing off". WorkManager reports
     * both as ENQUEUED, and telling the second one it is waiting for Wi-Fi is a lie the user
     * cannot act on.
     */
    data class Queued(val retrying: Boolean) : DownloadState()
    data class Running(val done: Long, val total: Long, val verifying: Boolean) : DownloadState()
    data object Installed : DownloadState()
    /**
     * [reason] is one of ModelDownloadWorker.REASON_*, or null when the worker never said.
     * [httpCode] is set only for REASON_HTTP.
     */
    data class Failed(val reason: String?, val httpCode: Int = 0) : DownloadState()
}

/**
 * Starts, watches, cancels and undoes a model download.
 *
 * Thin on purpose: WorkManager already owns "survives the app being killed", "resumes when
 * Wi-Fi comes back" and "only one of these at a time" (unique work). What is here is the
 * policy - which network, which model - and the translation from WorkInfo into something a
 * screen can draw.
 */
class ModelDownloadManager(
    private val context: Context,
    /**
     * The one engine, so deleting can make it let go of the file first. Null only in a context
     * that has no engine at all.
     */
    private val engine: LlmEngine? = null
) {

    private val workManager get() = WorkManager.getInstance(context)
    val store = ModelStore(context)

    /**
     * Begin, or resume, the download of [model].
     *
     * [allowMetered] is the user's explicit "download on mobile data" choice and defaults to
     * false everywhere it is offered: 1.6 GB on a Jordanian mobile bundle is real money, and an
     * app that spends it without being asked has no business calling itself private.
     */
    fun start(model: OnDeviceModel, allowMetered: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_MODEL_ID, model.id).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
                    )
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .addTag(TAG)
            .build()
        // KEEP, not REPLACE: tapping Download twice must not restart a download that is already
        // three quarters through.
        workManager.enqueueUniqueWork(uniqueName(model), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(model: OnDeviceModel) {
        workManager.cancelUniqueWork(uniqueName(model))
    }

    /**
     * Delete the file and report the bytes that came back.
     *
     * Three things in order, and the order is the whole point:
     *
     * 1. Cancel any download of the same model, or the worker carries on writing a `.part`
     *    next to the file the user just asked to be rid of.
     * 2. Make the engine let go and WAIT for it. A mapped `.task` keeps its inode alive after
     *    the unlink, so without this the file vanishes from the listing and the gigabyte does
     *    not come back until the idle timer happens to fire. The device showed exactly that.
     * 3. Only then unlink, and measure what was unlinked.
     */
    suspend fun delete(model: OnDeviceModel): Long {
        cancel(model)
        engine?.releaseAndWait()
        val freed = store.delete(model)
        revision.value += 1
        return freed
    }

    /**
     * Bumped whenever something changes that WorkManager cannot see.
     *
     * The card's state is derived from WorkInfo, and deleting a file changes no WorkInfo at
     * all - so after a delete the flow never re-emitted and the card sat there saying the
     * model was installed, with no Download button to press. Found on the device by Humam.
     */
    private val revision = MutableStateFlow(0)

    fun state(model: OnDeviceModel): Flow<DownloadState> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(uniqueName(model)),
            revision
        ) { infos, _ -> infos }.map { infos ->
            if (store.isInstalled(model)) return@map DownloadState.Installed
            val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: infos.firstOrNull { !it.state.isFinished }
                ?: infos.lastOrNull()
            when (info?.state) {
                WorkInfo.State.RUNNING -> DownloadState.Running(
                    done = info.progress.getLong(ModelDownloadWorker.KEY_DONE, 0L),
                    total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, model.bytes),
                    verifying = info.progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false)
                )
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    DownloadState.Queued(retrying = info.runAttemptCount > 0)
                WorkInfo.State.FAILED -> DownloadState.Failed(
                    reason = info.outputData.getString(ModelDownloadWorker.KEY_REASON),
                    httpCode = info.outputData.getInt(ModelDownloadWorker.KEY_HTTP_CODE, 0)
                )
                else -> DownloadState.Idle
            }
        }

    companion object {
        const val TAG = "model-download"
        fun uniqueName(model: OnDeviceModel) = "model-download-" + model.id
    }
}
