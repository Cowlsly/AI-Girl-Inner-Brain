package app.maskan.chat.data.backup

import android.content.Context
import android.content.SharedPreferences
import app.maskan.chat.data.repository.openEncryptedPrefsStrict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Reading the app's preferences out for the archive, and (session 8) putting them back.
 *
 * Everything in `shared_prefs/` is carried whole except what [BackupFormat.DENIED_PREFS] names.
 * Nothing here knows what any individual setting means, which is the point: a preference added
 * in 2.7 travels without anyone having to remember it exists.
 */
object BackupPrefs {

    class PrefsFile(val name: String, val encrypted: Boolean, val values: Map<String, Any?>)

    fun directory(context: Context): File = File(context.applicationInfo.dataDir, "shared_prefs")

    /** The names (without `.xml`) of every preferences file this app has on disk. */
    fun names(context: Context): List<String> =
        directory(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.sorted()
            ?: emptyList()

    /**
     * Whether a file is an EncryptedSharedPreferences one, decided by the keyset marker androidx
     * writes into it - read through the PLAIN api, which cannot fail and cannot delete anything.
     * Probing by trying to open it encrypted would risk the recovery path in
     * `createEncryptedPrefsOrFallback`, which deletes the file it cannot read.
     */
    fun isEncrypted(context: Context, name: String): Boolean =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .contains(BackupFormat.ENCRYPTED_PREFS_MARKER)

    fun open(context: Context, name: String, encrypted: Boolean): SharedPreferences =
        if (encrypted) openEncryptedPrefsStrict(context, name)
        else context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun read(context: Context, name: String): PrefsFile {
        val encrypted = isEncrypted(context, name)
        val prefs = open(context, name, encrypted)
        val values = prefs.all.filterKeys { it != BackupFormat.ENCRYPTED_PREFS_MARKER }
        return PrefsFile(name, encrypted, values)
    }

    /**
     * `{"files": {"<name>": {"encrypted": true, "values": {"k": {"t": "s", "v": "..."}}}}}`
     *
     * The type tag is carried because SharedPreferences is typed and a restore that wrote an Int
     * back as a String would throw ClassCastException on the next read, on a phone whose owner
     * has just lost the original.
     */
    fun toJson(files: List<PrefsFile>): JsonObject = buildJsonObject {
        put("files", buildJsonObject {
            for (file in files) {
                put(file.name, buildJsonObject {
                    put("encrypted", JsonPrimitive(file.encrypted))
                    put("values", buildJsonObject {
                        for ((key, value) in file.values.entries.sortedBy { it.key }) {
                            val encoded = encode(value) ?: continue
                            put(key, encoded)
                        }
                    })
                })
            }
        })
    }

    private fun encode(value: Any?): JsonObject? = when (value) {
        is String -> tagged("s", JsonPrimitive(value))
        is Boolean -> tagged("b", JsonPrimitive(value))
        is Int -> tagged("i", JsonPrimitive(value))
        is Long -> tagged("l", JsonPrimitive(value))
        is Float -> tagged("f", JsonPrimitive(value))
        is Set<*> -> tagged("ss", JsonArray(value.map { JsonPrimitive(it as? String ?: it.toString()) }))
        else -> null
    }

    private fun tagged(type: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
        buildJsonObject {
            put("t", JsonPrimitive(type))
            put("v", value)
        }
}
