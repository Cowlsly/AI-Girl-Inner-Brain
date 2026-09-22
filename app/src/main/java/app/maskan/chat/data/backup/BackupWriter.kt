package app.maskan.chat.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.maskan.chat.BuildConfig
import app.maskan.chat.data.local.AppDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Writes the archive. [BackupFormat] says what the archive is; this says how it is made.
 *
 * Everything runs on [Dispatchers.IO] and nothing is held in memory that grows with the size of
 * the database: the inner zip is written straight through the cipher into the outer zip, into the
 * stream the system file picker handed us.
 *
 * ## The one temporary file, and why it is not plaintext
 *
 * The live database is SQLCipher under a random key kept in EncryptedSharedPreferences, so its
 * bytes cannot simply be copied into an archive that has to open on another phone. The snapshot
 * is taken with `sqlcipher_export` - the same call MaskanApplication.encryptDatabase already
 * uses - into a temporary file keyed by a NEW random key generated for this archive alone, and
 * that key travels in the manifest. Two things follow, both deliberate:
 *
 *  - no plaintext copy of anybody's chats is ever written to disk, not even for a moment;
 *  - the phone's own database key never leaves the phone.
 */
class BackupWriter(
    private val context: Context,
    private val database: AppDatabase
) {

    data class Inventory(val counts: BackupCounts, val estimatedBytes: Long)

    /** What the Backup screen states before the user has typed anything. */
    suspend fun inventory(): Inventory = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        val counts = BackupCounts(
            chats = countOf(db, "conversations"),
            messages = countOf(db, "messages"),
            folders = countOf(db, "folders"),
            documents = countOf(db, "documents"),
            apiKeys = apiKeyCount()
        )
        val pageCount = pragmaLong(db, "page_count")
        val pageSize = pragmaLong(db, "page_size")
        val prefsBytes = BackupPrefs.directory(context).listFiles()
            ?.filter { it.name.removeSuffix(".xml") !in BackupFormat.DENIED_PREFS }
            ?.sumOf { it.length() } ?: 0L
        Inventory(counts, pageCount * pageSize + prefsBytes + ZIP_OVERHEAD_BYTES)
    }

    /**
     * Writes the archive to [uri] and returns the header that went into it.
     *
     * Cancelling the calling coroutine leaves no file behind: the document the picker created is
     * deleted, and if the provider refuses to delete it, it is truncated to nothing.
     */
    suspend fun write(
        uri: Uri,
        password: String,
        onProgress: (Float) -> Unit
    ): BackupHeader = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "empty password" }
        clearStaleSnapshots()

        val snapshot = File(context.cacheDir, SNAPSHOT_PREFIX + System.currentTimeMillis() + ".db")
        val dbKeyHex = ArchiveCrypto.hex(ArchiveCrypto.randomBytes(32))
        var ok = false
        try {
            val schema = exportSnapshot(snapshot, dbKeyHex)
            val counts = countsFromSnapshot(snapshot, dbKeyHex, schema)
            val header = assemble(uri, snapshot, dbKeyHex, schema, counts, password, onProgress)
            ok = true
            header
        } finally {
            snapshot.delete()
            if (!ok) discard(uri)
        }
    }

    /**
     * The archive around a snapshot that already exists, at a schema the caller states.
     *
     * For the debug probe only, which needs archives at schemas this app no longer has so that
     * restore's migration path can be exercised on a real file. [write] is the only path a user
     * takes; this one trusts its caller about [schema] and [counts].
     */
    suspend fun writeFromSnapshot(
        uri: Uri,
        password: String,
        snapshot: File,
        dbKeyHex: String,
        schema: Int,
        counts: BackupCounts,
        onProgress: (Float) -> Unit
    ): BackupHeader = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "empty password" }
        var ok = false
        try {
            val header = assemble(uri, snapshot, dbKeyHex, schema, counts, password, onProgress)
            ok = true
            header
        } finally {
            if (!ok) discard(uri)
        }
    }

    // -- The snapshot ---------------------------------------------------

    /** Returns the schema version the snapshot carries, read back out of the file it wrote. */
    private suspend fun exportSnapshot(target: File, keyHex: String): Int {
        val db = database.openHelper.writableDatabase

        // The main database file on this phone is 4 KB and the -wal beside it is 400 KB: almost
        // everything a user has ever typed can be sitting in the write-ahead log. Checkpoint
        // first or the snapshot is an empty app.
        db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val userVersion = pragmaLong(db, "user_version").toInt()

        // A handle of our own, with a pool of exactly ONE connection, because ATTACH is
        // per-connection and Room's pool has several. Through Room: execSQL("ATTACH") turns WAL
        // off first and throws if any other thread holds a connection (the device found it at
        // boot; a user would have found it as "the backup was not written" at random), while
        // query("ATTACH") lands on a read connection and the export on the primary then answers
        // "unknown database". Opened WITHOUT the WAL flag, this handle serialises everything on
        // one connection and never reconfigures anything Room is using. Session 7's version
        // only worked because the WAL side effect collapsed Room's pool to one connection.
        val raw = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            context.getDatabasePath(BackupFormat.DATABASE_NAME).absolutePath,
            livePassphrase(),
            null,
            // CREATE_IF_NECESSARY is for the ATTACHED file, not this one: SQLite opens an
            // attached database with the main connection's flags, and without it the fresh
            // snapshot file cannot be created ("unable to open database", code 14).
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE or
                net.zetetic.database.sqlcipher.SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
            null
        ) ?: throw IOException("the live database would not open a second time")
        try {
            val journal = raw.rawQuery("PRAGMA journal_mode", null)
                .use { if (it.moveToFirst()) it.getString(0) else "?" }
            Log.d(TAG, "export connection open; journal_mode " + journal)

            // Interpolated, not bound: the key is our own 64 hex characters and the path is a
            // file we just named inside cacheDir. Neither can carry a quote.
            val path = target.absolutePath
            raw.execSQL("ATTACH DATABASE '" + path + "' AS " + ATTACH_NAME + " KEY '" + keyHex + "'")
            try {
                exportInto(raw)
                // Set explicitly rather than trusted to be copied. This one line is what lets an
                // archive from an older Maskan restore at all: Room reads user_version to decide
                // which migrations to run, and a snapshot that claims version 0 would be treated
                // as a brand-new database and rebuilt empty.
                raw.execSQL("PRAGMA " + ATTACH_NAME + ".user_version = " + userVersion)
            } finally {
                runCatching { raw.execSQL("DETACH DATABASE " + ATTACH_NAME) }
            }
        } finally {
            raw.close()
        }
        coroutineContext.ensureActive()
        return userVersion
    }

    /** The key Room opened the live database with - the same read MaskanApplication does. */
    private fun livePassphrase(): String =
        app.maskan.chat.data.repository.openEncryptedPrefsStrict(context, BackupFormat.DB_PREFS_NAME)
            .getString(BackupFormat.DB_PREFS_KEY, null)
            ?: throw IOException("no database key")

    /**
     * ONE call, and no retry around it.
     *
     * `execSQL` runs this statement and then throws, because it returns rows - so a catch that
     * falls back to `rawQuery` runs the export a SECOND time, into an attached database that now
     * already has the tables, and the device answers "table `conversations` already exists".
     * A retry wrapped around a call with side effects is not a safety net. `rawQuery` is the path
     * with the contract we want: the statement runs when the cursor is stepped.
     */
    private fun exportInto(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        db.rawQuery("SELECT sqlcipher_export('" + ATTACH_NAME + "')", null).use { it.moveToFirst() }
    }

    /**
     * Counts read from the SNAPSHOT, not from the live database - so the header cannot disagree
     * with the payload it describes. That the two also match the live database is what the
     * round-trip test proves.
     *
     * Opening the file here has a second job: an unreadable snapshot is found now, on the phone
     * that still has the data, rather than by the person restoring it.
     */
    private fun countsFromSnapshot(file: File, keyHex: String, expectedSchema: Int): BackupCounts {
        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            file.absolutePath,
            keyHex,
            null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
            null,
            null
        ) ?: throw IOException("the snapshot would not open")
        try {
            val schema = db.rawQuery("PRAGMA user_version", null).use {
                if (it.moveToFirst()) it.getInt(0) else -1
            }
            if (schema != expectedSchema) {
                throw IOException("snapshot schema is " + schema + ", expected " + expectedSchema)
            }
            return BackupCounts(
                chats = rawCount(db, "conversations"),
                messages = rawCount(db, "messages"),
                folders = rawCount(db, "folders"),
                documents = rawCount(db, "documents"),
                apiKeys = apiKeyCount()
            )
        } finally {
            db.close()
        }
    }

    // -- The archive ----------------------------------------------------

    private suspend fun assemble(
        uri: Uri,
        snapshot: File,
        dbKeyHex: String,
        schema: Int,
        counts: BackupCounts,
        password: String,
        onProgress: (Float) -> Unit
    ): BackupHeader {
        val now = System.currentTimeMillis()
        val app = BackupAppInfo(
            packageName = context.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE
        )

        val carried = mutableListOf<BackupPrefs.PrefsFile>()
        val skipped = mutableListOf<SkippedPrefs>()
        for (name in BackupPrefs.names(context)) {
            val denied = BackupFormat.DENIED_PREFS[name]
            if (denied != null) skipped += SkippedPrefs(name, denied)
            else carried += BackupPrefs.read(context, name)
        }
        val keyFiles = carried.filter { it.name == BackupFormat.KEYS_PREFS_NAME }
        val settingFiles = carried.filter { it.name != BackupFormat.KEYS_PREFS_NAME }

        val keysBytes = BackupFormat.json
            .encodeToString(kotlinx.serialization.json.JsonObject.serializer(), BackupPrefs.toJson(keyFiles))
            .toByteArray(Charsets.UTF_8)
        val settingsBytes = BackupFormat.json
            .encodeToString(kotlinx.serialization.json.JsonObject.serializer(), BackupPrefs.toJson(settingFiles))
            .toByteArray(Charsets.UTF_8)

        val manifest = BackupManifest(
            format = BackupFormat.FORMAT_VERSION,
            schema = schema,
            app = app,
            createdAtEpochMs = now,
            dbKey = dbKeyHex,
            dbBytes = snapshot.length(),
            dbSha256 = sha256Of(snapshot),
            counts = counts,
            entries = listOf(
                BackupFormat.INNER_MANIFEST,
                BackupFormat.INNER_KEYS,
                BackupFormat.INNER_SETTINGS,
                BackupFormat.INNER_DATABASE
            ),
            carriedPrefs = carried.map { it.name },
            skippedPrefs = skipped
        )
        val manifestBytes = BackupFormat.json
            .encodeToString(BackupManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)

        val header = BackupHeader(
            app = app,
            schema = schema,
            createdAt = BackupFormat.iso8601(now),
            createdAtEpochMs = now,
            keysIncluded = counts.apiKeys > 0,
            counts = counts,
            encryption = BackupEncryptionInfo(
                kdf = BackupFormat.KDF_NAME,
                iterations = BackupFormat.DEFAULT_ITERATIONS,
                cipher = BackupFormat.CIPHER_NAME,
                chunkBytes = BackupFormat.CHUNK_BYTES
            ),
            dbBytes = snapshot.length()
        )
        val headerBytes = BackupFormat.json
            .encodeToString(BackupHeader.serializer(), header)
            .toByteArray(Charsets.UTF_8)

        val salt = ArchiveCrypto.randomBytes(ArchiveCrypto.SALT_BYTES)
        val noncePrefix = ArchiveCrypto.randomBytes(ArchiveCrypto.NONCE_PREFIX_BYTES)
        val derived = ArchiveCrypto.derive(password, salt, BackupFormat.DEFAULT_ITERATIONS)
        coroutineContext.ensureActive()

        val prelude = ArchiveCrypto.Prelude(
            kdfId = ArchiveCrypto.KDF_PBKDF2_HMAC_SHA256_UTF8,
            cipherId = ArchiveCrypto.CIPHER_AES_256_GCM,
            iterations = BackupFormat.DEFAULT_ITERATIONS,
            chunkBytes = BackupFormat.CHUNK_BYTES,
            salt = salt,
            noncePrefix = noncePrefix,
            check = derived.checkValue()
        ).toBytes()
        val aad = ArchiveCrypto.aad(headerBytes, prelude)

        try {
            openForWrite(uri).use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { outer ->
                    storedEntry(outer, BackupFormat.ENTRY_HEADER, headerBytes)

                    // The payload is ciphertext: deflating it would cost CPU and add bytes.
                    outer.setLevel(Deflater.NO_COMPRESSION)
                    outer.putNextEntry(ZipEntry(BackupFormat.ENTRY_PAYLOAD))
                    outer.write(prelude)

                    val gcm = ArchiveCrypto.ChunkedGcmOutputStream(
                        outer, derived.encryptionKey, aad, noncePrefix, BackupFormat.CHUNK_BYTES
                    )
                    val inner = ZipOutputStream(gcm)
                    inner.setLevel(Deflater.BEST_SPEED)
                    deflatedEntry(inner, BackupFormat.INNER_MANIFEST, manifestBytes)
                    deflatedEntry(inner, BackupFormat.INNER_KEYS, keysBytes)
                    deflatedEntry(inner, BackupFormat.INNER_SETTINGS, settingsBytes)
                    onProgress(JSON_SHARE)

                    inner.setLevel(Deflater.NO_COMPRESSION)
                    inner.putNextEntry(ZipEntry(BackupFormat.INNER_DATABASE))
                    copyDatabase(snapshot, inner, onProgress)
                    inner.closeEntry()

                    inner.finish()
                    gcm.finish()
                    outer.closeEntry()
                    outer.finish()
                }
            }
        } finally {
            derived.clear()
        }
        onProgress(1f)
        return header
    }

    private suspend fun copyDatabase(
        snapshot: File,
        sink: ZipOutputStream,
        onProgress: (Float) -> Unit
    ) {
        val total = snapshot.length().coerceAtLeast(1L)
        var written = 0L
        var lastReported = -1
        FileInputStream(snapshot).use { source ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                coroutineContext.ensureActive()
                val n = source.read(buffer)
                if (n == -1) break
                sink.write(buffer, 0, n)
                written += n
                val step = (written * 100 / total).toInt()
                if (step != lastReported) {
                    lastReported = step
                    onProgress(JSON_SHARE + (1f - JSON_SHARE - TAIL_SHARE) * (written.toFloat() / total))
                }
            }
        }
    }

    private fun openForWrite(uri: Uri): java.io.OutputStream {
        val resolver = context.contentResolver
        // "wt" truncates, which matters if the picker handed back a file that already existed.
        // Not every document provider implements it; "w" is the fallback.
        return try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            Log.d(TAG, "wt refused (" + e.javaClass.simpleName + "), falling back to w")
            resolver.openOutputStream(uri, "w")
        } ?: throw IOException("the picker gave back a location that cannot be written")
    }

    /**
     * Leave nothing behind. The picker creates the document before we write a byte, so a cancel
     * or a failure has a zero-length (or half-written) file to answer for.
     */
    private fun discard(uri: Uri) {
        val deleted = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
        if (deleted) {
            Log.d(TAG, "discarded the unfinished archive")
            return
        }
        // Some providers refuse deleteDocument. An empty file is still a lie, but it is a
        // smaller one than a half archive that looks restorable.
        val truncated = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { }
            true
        }.getOrDefault(false)
        Log.w(TAG, "could not delete the unfinished archive; truncated=" + truncated)
    }

    // -- Small helpers --------------------------------------------------

    private fun storedEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun deflatedEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { source ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val n = source.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return ArchiveCrypto.hex(digest.digest())
    }

    private fun apiKeyCount(): Int =
        BackupPrefs.read(context, BackupFormat.KEYS_PREFS_NAME).values.keys
            .count { it.startsWith("provider_") && it.endsWith("_api_key") }

    private fun countOf(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM " + table).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun rawCount(db: net.zetetic.database.sqlcipher.SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM " + table, null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun pragmaLong(db: SupportSQLiteDatabase, name: String): Long =
        db.query("PRAGMA " + name).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** A crash during an earlier backup could have left one. It is encrypted, but it is also
     *  dead weight in cacheDir, and nobody is coming back for it. */
    private fun clearStaleSnapshots() {
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(SNAPSHOT_PREFIX) }
            ?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "MaskanBackup"
        private const val ATTACH_NAME = "mkbak"
        private const val SNAPSHOT_PREFIX = "backup-snapshot-"
        private const val COPY_BUFFER = 64 * 1024
        private const val ZIP_OVERHEAD_BYTES = 4 * 1024L

        /** The manifest, the keys and the settings, as a share of the progress bar. */
        private const val JSON_SHARE = 0.03f
        private const val TAIL_SHARE = 0.02f
    }
}
