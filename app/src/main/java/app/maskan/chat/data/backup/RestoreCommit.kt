package app.maskan.chat.data.backup

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import app.maskan.chat.data.repository.createEncryptedPrefsOrFallback
import app.maskan.chat.data.repository.openEncryptedPrefsStrict
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * The half of restore that changes the phone.
 *
 * [BackupRestorer] decrypts an archive into a staging directory and, once every check has
 * passed, writes a COMMIT marker. Nothing on the phone has changed at that point. The app then
 * restarts, and this runs at the top of `MaskanApplication.onCreate` - before Room, before any
 * preferences are read - and swaps the staged files in.
 *
 * Every step is idempotent, so a force-stop anywhere inside [applyIfPending] resumes on the next
 * start: the marker is the last thing deleted, and a stage without a marker is wiped. The
 * previous database is kept beside the new one as `.pre-restore` until the restored one has
 * opened once ([finishIfOpened]); until then the phone still holds what it had before.
 *
 * Generated pictures and clips in `filesDir/images` are NOT touched. A restore onto the same
 * phone is an older snapshot, and everything generated after it would become an orphan - the
 * user's own artwork, encrypted under a key that never leaves this phone, unrecoverable once
 * deleted. Disk is cheap. Humam's call, 2026-09-22.
 */
object RestoreCommit {

    private const val TAG = "MaskanRestore"
    private const val STAGE_DIR = "restore-stage"
    private const val FAILED_DIR = "restore-failed"
    const val MARKER = "COMMIT"
    const val PRE_RESTORE_SUFFIX = ".pre-restore"

    fun stageDir(context: Context): File = File(context.filesDir, STAGE_DIR)

    fun isPending(context: Context): Boolean = File(stageDir(context), MARKER).exists()

    /**
     * Applies a staged restore if one has been committed. Never throws: a restore that cannot be
     * applied is rolled back to the previous database and key, moved aside for diagnosis, and
     * logged, and the app opens with what it had.
     */
    fun applyIfPending(context: Context): Boolean {
        val stage = stageDir(context)
        val marker = File(stage, MARKER)
        if (!marker.exists()) {
            if (stage.exists()) {
                stage.deleteRecursively()
                Log.i(TAG, "wiped a stage that was never committed")
            }
            return false
        }
        waitForStagingProcess(marker)

        val dbPrefs = createEncryptedPrefsOrFallback(context, BackupFormat.DB_PREFS_NAME)
        val previousKey = dbPrefs.getString(BackupFormat.DB_PREFS_KEY, null)
        val live = context.getDatabasePath(BackupFormat.DATABASE_NAME)
        val previous = File(live.parentFile, live.name + PRE_RESTORE_SUFFIX)
        var movedLiveAside = false
        try {
            val manifest = BackupFormat.json.decodeFromString(
                BackupManifest.serializer(),
                File(stage, BackupFormat.INNER_MANIFEST).readText(Charsets.UTF_8)
            )
            Log.i(TAG, "applying a staged restore: " + manifest.counts + ", schema " + manifest.schema)

            // 1. A render still queued for the OLD data would write into message ids that now
            //    belong to the restored rows. WorkManager's queue survives process death; ours
            //    does not survive a restore.
            runCatching { WorkManager.getInstance(context).cancelAllWork() }
                .onFailure { Log.w(TAG, "cancelAllWork", it) }

            // 2. Preferences, keys first. Both are idempotent rewrites of whole files.
            applyPrefs(context, File(stage, BackupFormat.INNER_KEYS))
            applyPrefs(context, File(stage, BackupFormat.INNER_SETTINGS))

            // 3. The archive's database key becomes this phone's database key, under this
            //    phone's own Keystore. Written before the file moves so that a crash between
            //    the two is repaired by the next start re-running this, not by the user.
            if (!dbPrefs.edit().putString(BackupFormat.DB_PREFS_KEY, manifest.dbKey).commit()) {
                throw IOException("could not store the database key")
            }

            // 4. The database. Absent staged file = already moved by an earlier attempt.
            val staged = File(stage, BackupFormat.INNER_DATABASE)
            if (staged.exists()) {
                live.parentFile?.mkdirs()
                if (live.exists()) {
                    previous.delete()
                    sidecars(previous).forEach { it.delete() }
                    if (!live.renameTo(previous)) throw IOException("could not move the live database aside")
                    movedLiveAside = true
                }
                sidecars(live).forEach { it.delete() }
                if (!staged.renameTo(live)) throw IOException("could not move the restored database into place")
            }

            // 5. Only now is the restore a fact.
            if (!marker.delete()) throw IOException("could not delete the marker")
            stage.deleteRecursively()
            Log.i(TAG, "restore applied; the previous database is kept as " + previous.name +
                " until the restored one opens")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "restore could not be applied; rolling back", e)
            runCatching {
                if (movedLiveAside && !live.exists()) previous.renameTo(live)
                dbPrefs.edit().apply {
                    if (previousKey == null) remove(BackupFormat.DB_PREFS_KEY)
                    else putString(BackupFormat.DB_PREFS_KEY, previousKey)
                }.commit()
                val failed = File(context.filesDir, FAILED_DIR)
                failed.deleteRecursively()
                stage.renameTo(failed)
            }.onFailure { Log.e(TAG, "rollback failed too", it) }
            return false
        }
    }

    /**
     * Deletes the `.pre-restore` database once the restored one has opened - [open] must run a
     * real query through Room, so that the migrations have run too. If it throws, the previous
     * database stays where it is and the log says so.
     */
    fun finishIfOpened(context: Context, open: () -> Unit) {
        val live = context.getDatabasePath(BackupFormat.DATABASE_NAME)
        val previous = File(live.parentFile, live.name + PRE_RESTORE_SUFFIX)
        if (!previous.exists() || isPending(context)) return
        try {
            open()
        } catch (e: Throwable) {
            Log.e(TAG, "the restored database did not open; keeping " + previous.name, e)
            return
        }
        previous.delete()
        sidecars(previous).forEach { it.delete() }
        Log.i(TAG, "the restored database opened; " + previous.name + " deleted")
    }

    private fun applyPrefs(context: Context, file: File) {
        if (!file.exists()) return
        val json = BackupFormat.json.decodeFromString(
            JsonObject.serializer(), file.readText(Charsets.UTF_8)
        )
        val written = BackupPrefs.apply(context, json) { name -> openEncryptedForWrite(context, name) }
        Log.i(TAG, file.name + ": wrote " + written)
    }

    /**
     * Strict first, so a Keystore that cannot serve us throws instead of handing back the
     * in-memory fallback that would swallow the keys and report success. A file that exists and
     * is unreadable is replaced: it is about to be overwritten anyway.
     */
    private fun openEncryptedForWrite(context: Context, name: String) = try {
        openEncryptedPrefsStrict(context, name)
    } catch (e: Exception) {
        Log.w(TAG, name + " would not open; replacing it", e)
        context.deleteSharedPreferences(name)
        openEncryptedPrefsStrict(context, name)
    }

    private fun sidecars(db: File): List<File> =
        listOf("-wal", "-shm", "-journal").map { File(db.parentFile, db.name + it) }

    /**
     * The marker names the pid of the process that staged the restore. That process is meant to
     * be dead by now; if it is still winding down, wait for it rather than swap a database it
     * may still hold open.
     */
    private fun waitForStagingProcess(marker: File) {
        val pid = runCatching { marker.readText().lineSequence().first().trim().toInt() }.getOrNull()
            ?: return
        if (pid == android.os.Process.myPid()) return
        val proc = File("/proc/" + pid)
        var waited = 0
        while (proc.exists() && waited < 3000) {
            Thread.sleep(50)
            waited += 50
        }
        if (proc.exists()) Log.w(TAG, "process " + pid + " still alive after " + waited + " ms")
        else if (waited > 0) Log.i(TAG, "waited " + waited + " ms for process " + pid)
    }
}
