package app.maskan.chat.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The shape of a Maskan backup file, and the only place its constants are written down.
 *
 * The compatibility contract is `Maskan/2.6_sessions/BACKUP_FORMAT.md`. The short version:
 *
 * ```
 * maskan-backup-YYYY-MM-DD.mkb        a zip
 * |- maskan-header.json               PLAINTEXT, first entry, STORED
 * '- maskan-payload.bin               prelude + AES-256-GCM chunks
 *    '- (decrypted) an inner zip
 *       |- manifest.json              the authoritative inventory
 *       |- keys.json                  maskan_secure_prefs (the API keys)
 *       |- settings.json              every other carried preferences file
 *       '- database.db                a SQLCipher snapshot, keyed by manifest.dbKey
 * ```
 *
 * **The header is plaintext by design.** Restore shows the user what they are about to overwrite
 * - made on this date, by this version, this many chats, keys included - and asks for the
 * password only after they have said yes. A header you must decrypt to read cannot do that job,
 * so nobody may "harden" this by encrypting it: doing so silently breaks the confirmation screen
 * that makes restore safe. The accepted cost, stated once and not fixed: a file sitting in
 * Downloads reveals how many chats the owner has and that their API keys are in it.
 */
object BackupFormat {

    const val FORMAT_VERSION = 1
    const val KIND = "maskan-backup"
    const val EXTENSION = "mkb"

    const val ENTRY_HEADER = "maskan-header.json"
    const val ENTRY_PAYLOAD = "maskan-payload.bin"

    const val INNER_MANIFEST = "manifest.json"
    const val INNER_KEYS = "keys.json"
    const val INNER_SETTINGS = "settings.json"
    const val INNER_DATABASE = "database.db"

    const val KDF_NAME = "pbkdf2-hmac-sha256-utf8"
    const val CIPHER_NAME = "aes-256-gcm"

    /**
     * Measured, not guessed: 600,000 cost 5,302 ms on the Pixel 10 Pro - and a backup and a
     * restore each pay it once. 220,000 is ~1 s on that phone, which is the budget, and every
     * one of those iterations is real work an attacker must also do (see ArchiveCrypto.derive).
     * A cheaper phone pays more; this is a flagship number.
     *
     * The count travels in the archive's own prelude, so raising it later costs nothing and
     * orphans no existing file.
     */
    const val DEFAULT_ITERATIONS = 220_000

    const val CHUNK_BYTES = 64 * 1024

    /** A header or manifest larger than this is not one. */
    const val MAX_JSON_BYTES = 1 shl 20

    /** The database file inside the archive, and the live one it is restored to. */
    const val DATABASE_NAME = "privacyai_database"

    /** Where the SQLCipher key of the LIVE database lives. Never carried - see [DENIED_PREFS]. */
    const val DB_PREFS_NAME = "maskan_db_prefs"
    const val DB_PREFS_KEY = "db_encryption_key"

    /** The marker androidx.security writes into an EncryptedSharedPreferences file. */
    const val ENCRYPTED_PREFS_MARKER = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

    /** The one preferences file that holds the API keys, kept separate so "keys included" is a
     *  file that is present or absent rather than a flag somebody has to honour. */
    const val KEYS_PREFS_NAME = "maskan_secure_prefs"

    /**
     * Preferences files that must NEVER travel, and why.
     *
     * Every other file in `shared_prefs/` is carried whole and unread. A whitelist of settings
     * rots: the day somebody adds a preference and forgets to list it, it silently stops being
     * backed up and nobody finds out until a restore. A blanket copy rots in the other
     * direction, which is what this list is for.
     *
     * **The rule, for whoever adds the next preference: anything bound to THIS device - a
     * keystore alias, a device token, an installation id, a key that only the local Keystore can
     * unwrap - belongs in this list.** `maskan_db_prefs` is here because it holds the SQLCipher
     * key of the phone's own database; the archive carries its own key in the manifest instead.
     */
    val DENIED_PREFS: Map<String, String> = mapOf(
        DB_PREFS_NAME to "device-bound: the SQLCipher key of this phone's database"
    )

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    fun suggestedFileName(epochMs: Long): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(epochMs))
        return "maskan-backup-" + date + "." + EXTENSION
    }

    fun iso8601(epochMs: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(epochMs))
    }
}

@Serializable
data class BackupCounts(
    val chats: Int = 0,
    val messages: Int = 0,
    val folders: Int = 0,
    val documents: Int = 0,
    val apiKeys: Int = 0
)

@Serializable
data class BackupAppInfo(
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: Int = 0
)

@Serializable
data class BackupEncryptionInfo(
    val kdf: String = BackupFormat.KDF_NAME,
    val iterations: Int = BackupFormat.DEFAULT_ITERATIONS,
    val cipher: String = BackupFormat.CIPHER_NAME,
    val chunkBytes: Int = BackupFormat.CHUNK_BYTES
)

/**
 * The plaintext first entry. Everything a person needs to choose between two files, and nothing
 * that would help somebody who stole one.
 */
@Serializable
data class BackupHeader(
    val kind: String = BackupFormat.KIND,
    val format: Int = BackupFormat.FORMAT_VERSION,
    val app: BackupAppInfo = BackupAppInfo(),
    val schema: Int = 0,
    val createdAt: String = "",
    val createdAtEpochMs: Long = 0L,
    val keysIncluded: Boolean = false,
    val counts: BackupCounts = BackupCounts(),
    val encryption: BackupEncryptionInfo = BackupEncryptionInfo(),
    val dbBytes: Long = 0L
)

@Serializable
data class SkippedPrefs(val name: String = "", val reason: String = "")

/**
 * The inventory inside the encrypted payload. The counts here are the ones computed from the
 * snapshot that is actually in the file; the header's copy of them is written from these, so a
 * header that disagrees with its payload is a file somebody edited.
 */
@Serializable
data class BackupManifest(
    val format: Int = BackupFormat.FORMAT_VERSION,
    val schema: Int = 0,
    val app: BackupAppInfo = BackupAppInfo(),
    val createdAtEpochMs: Long = 0L,
    /** The SQLCipher passphrase of `database.db`: 64 hex characters, generated for this archive
     *  alone. The phone's own key never leaves the phone. */
    val dbKey: String = "",
    val dbBytes: Long = 0L,
    val dbSha256: String = "",
    val counts: BackupCounts = BackupCounts(),
    val entries: List<String> = emptyList(),
    val carriedPrefs: List<String> = emptyList(),
    val skippedPrefs: List<SkippedPrefs> = emptyList()
)
