package app.maskan.chat.data.backup

import android.content.Context
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The half of restore that changes nothing.
 *
 * Decrypts an archive into [RestoreCommit.stageDir], proves the database inside opens on THIS
 * phone with the key the manifest carries and holds what the manifest says, and only then writes
 * the COMMIT marker. Until the marker exists the phone has exactly what it had; cancelling or
 * failing wipes the stage. [RestoreCommit.applyIfPending] does the rest at the next start.
 */
class BackupRestorer(
    private val context: Context,
    private val reader: BackupReader
) {

    class Staged(val header: BackupHeader, val manifest: BackupManifest)

    suspend fun stage(uri: Uri, password: String): Staged = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "empty password" }
        val stage = RestoreCommit.stageDir(context)
        // A stage from an earlier restore that never restarted is superseded, marker and all.
        stage.deleteRecursively()
        stage.mkdirs()
        var ok = false
        try {
            val extracted = reader.extractTo(uri, password, stage)
            coroutineContext.ensureActive()
            verify(extracted)
            File(stage, BackupFormat.INNER_MANIFEST).writeText(
                BackupFormat.json.encodeToString(BackupManifest.serializer(), extracted.manifest),
                Charsets.UTF_8
            )
            // The marker is written whole under another name and renamed into place, so it is
            // either there or not: a half-written marker cannot exist.
            val tmp = File(stage, RestoreCommit.MARKER + ".tmp")
            tmp.writeText(Process.myPid().toString() + "\n" + extracted.manifest.createdAtEpochMs + "\n")
            if (!tmp.renameTo(File(stage, RestoreCommit.MARKER))) {
                throw IOException("could not commit the stage")
            }
            ok = true
            Staged(extracted.header, extracted.manifest)
        } finally {
            if (!ok) stage.deleteRecursively()
        }
    }

    /**
     * Opens the staged snapshot with the manifest's key. This is the check that the archive is
     * restorable ON THIS PHONE - its SQLCipher, its Keystore - made while the phone still has its
     * own data, rather than discovered after the swap by an app that cannot start.
     */
    private fun verify(extracted: BackupReader.Extracted) {
        val manifest = extracted.manifest
        val db = try {
            net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                extracted.database.absolutePath,
                manifest.dbKey,
                null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
        } catch (e: Exception) {
            throw ArchiveException(ArchiveRefusal.DAMAGED, "the database inside would not open", e)
        } ?: throw ArchiveException(ArchiveRefusal.DAMAGED, "the database inside would not open")
        try {
            val version = db.rawQuery("PRAGMA user_version", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
            if (version != manifest.schema) {
                throw ArchiveException(
                    ArchiveRefusal.DAMAGED,
                    "database says schema " + version + ", the manifest says " + manifest.schema
                )
            }
            val counts = BackupCounts(
                chats = count(db, "conversations"),
                messages = count(db, "messages"),
                folders = count(db, "folders"),
                documents = count(db, "documents"),
                apiKeys = manifest.counts.apiKeys
            )
            if (counts != manifest.counts) {
                throw ArchiveException(
                    ArchiveRefusal.DAMAGED,
                    "database holds " + counts + ", the manifest says " + manifest.counts
                )
            }
        } finally {
            db.close()
        }
    }

    /** A table an older schema never had counts as zero, which is what its manifest says. */
    private fun count(db: net.zetetic.database.sqlcipher.SQLiteDatabase, table: String): Int {
        val exists = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)
        ).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (!exists) return 0
        return db.rawQuery("SELECT COUNT(*) FROM " + table, null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }
}
