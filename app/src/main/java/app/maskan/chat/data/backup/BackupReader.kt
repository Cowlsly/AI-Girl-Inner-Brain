package app.maskan.chat.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reading an archive back.
 *
 * Session 7 needs this to prove that what it wrote can be read - a writer that reports success
 * over an incomplete file is the exact failure this project has been burned by twice, and the
 * only way to know is to open the thing. Session 8 builds restore on top of it.
 *
 * Two entry points, in the order restore must use them:
 *  - [readHeader] needs NO password. It is what the confirmation screen shows.
 *  - [extractTo] needs the password, and hands back the manifest and the files.
 */
class BackupReader(
    private val context: Context,
    private val currentSchema: () -> Int
) {

    class HeaderResult(val header: BackupHeader, val raw: ByteArray)

    /**
     * The plaintext header, with the archive refused by name if this app cannot read it.
     *
     * Nothing is decrypted here and no password is involved, which is the whole point: a person
     * choosing between two files has to be able to see which is which before they type anything.
     */
    suspend fun readHeader(uri: Uri): HeaderResult = withContext(Dispatchers.IO) {
        val raw = openZip(uri, ArchiveRefusal.NOT_A_BACKUP) { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == BackupFormat.ENTRY_HEADER) {
                    found = ArchiveCrypto.readAll(zip, BackupFormat.MAX_JSON_BYTES)
                    break
                }
                zip.closeEntry()
            }
            found
        } ?: throw ArchiveException(ArchiveRefusal.NOT_A_BACKUP, "no " + BackupFormat.ENTRY_HEADER)

        val header = try {
            BackupFormat.json.decodeFromString(BackupHeader.serializer(), raw.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ArchiveException(ArchiveRefusal.NOT_A_BACKUP, "the header is not Maskan's", e)
        }
        validate(header)
        HeaderResult(header, raw)
    }

    private fun validate(header: BackupHeader) {
        if (header.kind != BackupFormat.KIND) {
            throw ArchiveException(ArchiveRefusal.NOT_A_BACKUP, "kind=" + header.kind)
        }
        if (header.format > BackupFormat.FORMAT_VERSION) {
            throw ArchiveException(
                ArchiveRefusal.NEWER_FORMAT,
                "format " + header.format + ", this app reads " + BackupFormat.FORMAT_VERSION
            )
        }
        val schema = currentSchema()
        if (header.schema > schema) {
            // A database can be migrated forwards and never backwards. Refused by name rather
            // than half-read.
            throw ArchiveException(
                ArchiveRefusal.NEWER_SCHEMA,
                "schema " + header.schema + ", this app has " + schema
            )
        }
    }

    class Extracted(
        val header: BackupHeader,
        val manifest: BackupManifest,
        val database: File,
        val keys: File?,
        val settings: File?
    )

    /**
     * Decrypts into [directory] and returns what was in it.
     *
     * A wrong password is refused by the password check in the prelude BEFORE any ciphertext is
     * touched, so the two failures a person can actually have - "I mistyped" and "this file is
     * damaged" - arrive as two different answers rather than one shrug.
     */
    suspend fun extractTo(
        uri: Uri,
        password: String,
        directory: File
    ): Extracted = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "empty password" }
        val headerResult = readHeader(uri)
        directory.mkdirs()

        // NOT_A_BACKUP above, DAMAGED here: readHeader has already succeeded, so this file
        // has said what it is. A failure from this point is a broken archive, not a stranger.
        openZip(uri, ArchiveRefusal.DAMAGED) { zip ->
            var sawHeader = false
            var extracted: Extracted? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    BackupFormat.ENTRY_HEADER -> {
                        sawHeader = true
                        ArchiveCrypto.readAll(zip, BackupFormat.MAX_JSON_BYTES)
                    }
                    BackupFormat.ENTRY_PAYLOAD -> {
                        if (!sawHeader) {
                            throw ArchiveException(
                                ArchiveRefusal.NOT_A_BACKUP,
                                "the payload came before the header"
                            )
                        }
                        extracted = readPayload(zip, headerResult, password, directory)
                    }
                    else -> zip.closeEntry()
                }
            }
            extracted ?: throw ArchiveException(
                ArchiveRefusal.DAMAGED,
                "no " + BackupFormat.ENTRY_PAYLOAD
            )
        }
    }

    private fun readPayload(
        source: InputStream,
        headerResult: HeaderResult,
        password: String,
        directory: File
    ): Extracted {
        val prelude = ArchiveCrypto.Prelude.parse(
            ArchiveCrypto.readExactly(source, ArchiveCrypto.PRELUDE_BYTES)
        )
        val derived = ArchiveCrypto.derive(password, prelude.salt, prelude.iterations)
        try {
            if (!derived.matches(prelude.check)) {
                throw ArchiveException(ArchiveRefusal.WRONG_PASSWORD, "password check failed")
            }
            val aad = ArchiveCrypto.aad(headerResult.raw, prelude.toBytes())
            val plain = ArchiveCrypto.ChunkedGcmInputStream(
                source, derived.encryptionKey, aad, prelude.noncePrefix, prelude.chunkBytes
            )
            val inner = ZipInputStream(plain)
            var manifest: BackupManifest? = null
            var database: File? = null
            var keys: File? = null
            var settings: File? = null
            while (true) {
                val entry = inner.nextEntry ?: break
                when (entry.name) {
                    BackupFormat.INNER_MANIFEST -> {
                        val bytes = ArchiveCrypto.readAll(inner, BackupFormat.MAX_JSON_BYTES)
                        manifest = BackupFormat.json.decodeFromString(
                            BackupManifest.serializer(), bytes.toString(Charsets.UTF_8)
                        )
                    }
                    BackupFormat.INNER_KEYS -> keys = spill(inner, File(directory, BackupFormat.INNER_KEYS))
                    BackupFormat.INNER_SETTINGS ->
                        settings = spill(inner, File(directory, BackupFormat.INNER_SETTINGS))
                    BackupFormat.INNER_DATABASE ->
                        database = spill(inner, File(directory, BackupFormat.INNER_DATABASE))
                    else -> inner.closeEntry()
                }
            }
            val found = manifest
                ?: throw ArchiveException(ArchiveRefusal.DAMAGED, "no manifest in the payload")
            val db = database
                ?: throw ArchiveException(ArchiveRefusal.DAMAGED, "no database in the payload")
            if (db.length() != found.dbBytes) {
                throw ArchiveException(
                    ArchiveRefusal.DAMAGED,
                    "database is " + db.length() + " bytes, the manifest says " + found.dbBytes
                )
            }
            // The last check, and the one that makes the rest of them a proof rather than a
            // hope: the database that came out is the database that went in, byte for byte.
            // The GCM tags already say no chunk was altered; this says none went missing and
            // none arrived in the wrong order.
            val digest = sha256Of(db)
            if (found.dbSha256.isNotEmpty() && digest != found.dbSha256) {
                throw ArchiveException(
                    ArchiveRefusal.DAMAGED,
                    "database hashes to " + digest + ", the manifest says " + found.dbSha256
                )
            }
            return Extracted(headerResult.header, found, db, keys, settings)
        } finally {
            derived.clear()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return ArchiveCrypto.hex(digest.digest())
    }

    private fun spill(source: InputStream, target: File): File {
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n == -1) break
                out.write(buffer, 0, n)
            }
        }
        return target
    }

    /**
     * [whenBroken] is the answer for an unreadable container: NOT_A_BACKUP while we are still
     * asking what this file is, DAMAGED once it has told us.
     */
    private fun <T> openZip(uri: Uri, whenBroken: ArchiveRefusal, block: (ZipInputStream) -> T): T {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw ArchiveException(ArchiveRefusal.NOT_A_BACKUP, "the file could not be opened", e)
        } ?: throw ArchiveException(ArchiveRefusal.NOT_A_BACKUP, "the file could not be opened")
        return stream.use { raw ->
            ZipInputStream(java.io.BufferedInputStream(raw)).use { zip ->
                try {
                    block(zip)
                } catch (e: ArchiveException) {
                    throw e
                } catch (e: IOException) {
                    // A renamed JPEG and a text file arrive here as NOT_A_BACKUP; a Maskan
                    // archive with its tail cut off arrives here as DAMAGED.
                    throw ArchiveException(whenBroken, "not a readable archive", e)
                }
            }
        }
    }
}
