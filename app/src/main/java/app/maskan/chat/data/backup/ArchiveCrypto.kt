package app.maskan.chat.data.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of a Maskan backup archive: one password in, one authenticated stream out.
 *
 * `BACKUP_FORMAT.md` is the contract this file implements. Nothing here may change in a way that
 * makes a file written by 2.6 unreadable by a later version - a reader honours the parameters
 * recorded in the archive's own prelude, never a constant compiled into it.
 *
 * ## Why the key derivation is written out by hand
 *
 * `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` takes a `char[]`, and what a provider
 * does with the characters is the provider's business. Android's older BouncyCastle path runs a
 * password through `PBEParametersGenerator.PKCS5PasswordToBytes` - the low eight bits of each
 * char and nothing else - which would collapse the 26 UTF-8 bytes of the Arabic fixture password
 * in BACKUP_FORMAT.md into 14 bytes of junk. It would still appear to work, because the same
 * mangling happens every time on the same provider, and it would stop working the day the
 * provider changed or another implementation read the file.
 *
 * MEASURED on the Pixel 10 Pro (2026-09-22): this device's factory agrees with UTF-8 byte for
 * byte, so it is NOT mangling anything today. That is a fact about one phone and one Android
 * version - and a backup format has to be read by implementations neither of which we have seen.
 * So the password is hashed as its UTF-8 bytes, by the loop below: no provider gets a vote. The
 * HMAC is still the platform's (javax.crypto.Mac); only the PBKDF2 iteration is ours, and it is
 * checked against RFC 6070 and published HMAC-SHA256 vectors on every probe run before it is
 * trusted - see BackupProbeReceiver.
 *
 * ## The stream
 *
 * AES-256-GCM in 64 KiB chunks rather than one CipherOutputStream, for three reasons that all
 * matter to a file this app must be able to refuse safely:
 *  - a 40 MB archive never has to fit in memory;
 *  - a truncated file is DETECTED as truncated, because the last chunk says it is the last one
 *    in its own nonce and a cut file never reaches it;
 *  - decryption emits no unverified plaintext: each chunk's tag is checked before its bytes are
 *    handed on.
 */
object ArchiveCrypto {

    /** MKB1 - the payload's own magic, independent of the zip around it. */
    private val MAGIC = byteArrayOf(0x4D, 0x4B, 0x42, 0x31)

    const val KDF_PBKDF2_HMAC_SHA256_UTF8 = 1
    const val CIPHER_AES_256_GCM = 1

    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 7
    const val CHECK_BYTES = 16
    const val KEY_BYTES = 32
    const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8

    /** magic(4) kdf(1) cipher(1) iterations(4) chunk(4) salt(16) noncePrefix(7) check(16). */
    const val PRELUDE_BYTES = 4 + 1 + 1 + 4 + 4 + SALT_BYTES + NONCE_PREFIX_BYTES + CHECK_BYTES

    /** 8 MiB. A length field larger than this is corruption, not a chunk, and is refused
     *  before anything tries to allocate it. */
    const val MAX_CHUNK_BYTES = 8 * 1024 * 1024

    /**
     * PBKDF2 over the password's UTF-8 bytes. [algorithm] is a javax.crypto.Mac name; the format
     * always uses HmacSHA256, and the self-test also drives it with HmacSHA1 so the loop can be
     * checked against RFC 6070's published answers.
     */
    fun pbkdf2(
        algorithm: String,
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        lengthBytes: Int
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(lengthBytes > 0) { "length must be positive" }
        require(password.isNotEmpty()) { "empty password" }

        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(password, algorithm))
        val hLen = mac.macLength
        val blocks = (lengthBytes + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        val counter = ByteArray(4)

        for (block in 1..blocks) {
            counter[0] = (block ushr 24).toByte()
            counter[1] = (block ushr 16).toByte()
            counter[2] = (block ushr 8).toByte()
            counter[3] = block.toByte()

            mac.update(salt)
            mac.update(counter)
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)

            for (round in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in 0 until hLen) {
                    t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (block - 1) * hLen, hLen)
        }
        u.fill(0)
        t.fill(0)
        return if (out.size == lengthBytes) out else out.copyOf(lengthBytes)
    }

    /**
     * What a password becomes: 32 bytes of key and 32 bytes of verifier.
     *
     * The verifier is why a wrong password and a damaged file are two different sentences in the
     * UI. With the GCM tag alone they are one exception, and "your file is broken" told to
     * somebody who simply mistyped is the worst possible answer.
     */
    class DerivedKeys(val encryptionKey: ByteArray, private val verifier: ByteArray) {
        fun checkValue(): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(verifier).copyOf(CHECK_BYTES)

        fun matches(check: ByteArray): Boolean = constantTimeEquals(checkValue(), check)

        fun clear() {
            encryptionKey.fill(0)
            verifier.fill(0)
        }
    }

    /**
     * PBKDF2 for exactly ONE 32-byte block, then two HMAC labels off it.
     *
     * Asking PBKDF2 for 64 bytes would be two blocks, and the second block is where the password
     * check would live - so somebody grinding guesses against the check would compute one block
     * per guess while the person who owns the file computes two. Half price for the attacker,
     * full price for the owner, for nothing. One block, expanded, costs the attacker exactly what
     * it costs the owner, and it buys back the iterations to spend on the part that is hard.
     */
    fun derive(password: String, salt: ByteArray, iterations: Int): DerivedKeys {
        val bytes = password.toByteArray(Charsets.UTF_8)
        try {
            val master = pbkdf2("HmacSHA256", bytes, salt, iterations, KEY_BYTES)
            try {
                return DerivedKeys(expand(master, LABEL_KEY), expand(master, LABEL_CHECK))
            } finally {
                master.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun expand(master: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    /** Written down because they are part of the format: change a byte of either and every
     *  archive ever written stops opening. */
    const val LABEL_KEY = "maskan-backup-key-v1"
    const val LABEL_CHECK = "maskan-backup-check-v1"

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    // -- The prelude ----------------------------------------------------

    class Prelude(
        val kdfId: Int,
        val cipherId: Int,
        val iterations: Int,
        val chunkBytes: Int,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
        val check: ByteArray
    ) {
        fun toBytes(): ByteArray {
            val out = ByteArray(PRELUDE_BYTES)
            var p = 0
            System.arraycopy(MAGIC, 0, out, p, 4)
            p += 4
            out[p++] = kdfId.toByte()
            out[p++] = cipherId.toByte()
            p = putInt(out, p, iterations)
            p = putInt(out, p, chunkBytes)
            System.arraycopy(salt, 0, out, p, SALT_BYTES)
            p += SALT_BYTES
            System.arraycopy(noncePrefix, 0, out, p, NONCE_PREFIX_BYTES)
            p += NONCE_PREFIX_BYTES
            System.arraycopy(check, 0, out, p, CHECK_BYTES)
            return out
        }

        companion object {
            fun parse(raw: ByteArray): Prelude {
                if (raw.size != PRELUDE_BYTES) {
                    throw ArchiveException(ArchiveRefusal.DAMAGED, "prelude is " + raw.size + " bytes")
                }
                for (i in 0 until 4) {
                    if (raw[i] != MAGIC[i]) {
                        throw ArchiveException(ArchiveRefusal.NOT_A_BACKUP, "payload magic")
                    }
                }
                val kdf = raw[4].toInt() and 0xFF
                val cipher = raw[5].toInt() and 0xFF
                if (kdf != KDF_PBKDF2_HMAC_SHA256_UTF8 || cipher != CIPHER_AES_256_GCM) {
                    // A later Maskan may add an algorithm; this one refuses by name rather than
                    // guessing, which is the whole rule for a version it cannot read.
                    throw ArchiveException(
                        ArchiveRefusal.NEWER_FORMAT,
                        "kdf=" + kdf + " cipher=" + cipher
                    )
                }
                val iterations = getInt(raw, 6)
                val chunk = getInt(raw, 10)
                if (iterations <= 0 || chunk <= 0 || chunk > MAX_CHUNK_BYTES) {
                    throw ArchiveException(
                        ArchiveRefusal.DAMAGED,
                        "iterations=" + iterations + " chunk=" + chunk
                    )
                }
                return Prelude(
                    kdfId = kdf,
                    cipherId = cipher,
                    iterations = iterations,
                    chunkBytes = chunk,
                    salt = raw.copyOfRange(14, 14 + SALT_BYTES),
                    noncePrefix = raw.copyOfRange(30, 30 + NONCE_PREFIX_BYTES),
                    check = raw.copyOfRange(37, 37 + CHECK_BYTES)
                )
            }
        }
    }

    /**
     * What every chunk is authenticated against: the plaintext header and the prelude, both of
     * which a reader has already seen when it decrypts. Tampering with either - the counts shown
     * to the user before they type a password, the iteration count, the salt - fails the tag
     * rather than passing quietly.
     */
    fun aad(headerBytes: ByteArray, preludeBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(headerBytes) + preludeBytes

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, NONCE_PREFIX_BYTES)
        n[7] = (index ushr 24).toByte()
        n[8] = (index ushr 16).toByte()
        n[9] = (index ushr 8).toByte()
        n[10] = index.toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    private fun cipher(mode: Int, key: ByteArray, aad: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }

    // -- The streams ----------------------------------------------------

    /**
     * Writes [len][chunk] ... [0]. A chunk is emitted only once more data has arrived, so the
     * chunk that carries the `last` flag really is the last one - which is what makes a cut file
     * detectable.
     *
     * [finish] writes the final chunk and the terminator and does NOT close the sink: the sink is
     * a zip entry that still has a central directory to write.
     */
    class ChunkedGcmOutputStream(
        private val sink: OutputStream,
        private val key: ByteArray,
        private val aad: ByteArray,
        private val noncePrefix: ByteArray,
        private val chunkBytes: Int,
        private val onPlainBytes: (Int) -> Unit = {}
    ) : OutputStream() {

        private val buffer = ByteArray(chunkBytes)
        private var filled = 0
        private var index = 0
        private var finished = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished) { "stream already finished" }
            var from = off
            var left = len
            while (left > 0) {
                if (filled == chunkBytes) emit(last = false)
                val n = minOf(chunkBytes - filled, left)
                System.arraycopy(b, from, buffer, filled, n)
                filled += n
                from += n
                left -= n
            }
        }

        private fun emit(last: Boolean) {
            val ct = cipher(Cipher.ENCRYPT_MODE, key, aad, nonce(noncePrefix, index, last))
                .doFinal(buffer, 0, filled)
            writeInt(sink, ct.size)
            sink.write(ct)
            onPlainBytes(filled)
            filled = 0
            index++
        }

        fun finish() {
            if (finished) return
            finished = true
            emit(last = true)
            writeInt(sink, 0)
            sink.flush()
        }

        override fun close() {
            finish()
        }
    }

    /**
     * The mirror: reads one chunk ahead so it knows whether the chunk in hand is the last one
     * before it decrypts it. EOF where the terminator should be is a truncated file.
     */
    class ChunkedGcmInputStream(
        private val source: InputStream,
        private val key: ByteArray,
        private val aad: ByteArray,
        private val noncePrefix: ByteArray,
        private val chunkBytes: Int
    ) : InputStream() {

        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var finished = false
        private var pending: ByteArray? = null
        private var started = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) {
                if (!fill()) return -1
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        private fun fill(): Boolean {
            if (finished) return false
            val current = pending ?: run {
                if (started) throw ArchiveException(ArchiveRefusal.DAMAGED, "chunk stream ended early")
                started = true
                val len = readLength() ?: throw ArchiveException(ArchiveRefusal.DAMAGED, "no payload")
                if (len == 0) throw ArchiveException(ArchiveRefusal.DAMAGED, "payload has no chunks")
                readExactly(source, len)
            }
            val nextLen = readLength()
                ?: throw ArchiveException(ArchiveRefusal.DAMAGED, "truncated archive")
            val last = nextLen == 0
            plain = try {
                cipher(Cipher.DECRYPT_MODE, key, aad, nonce(noncePrefix, index, last)).doFinal(current)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw ArchiveException(ArchiveRefusal.DAMAGED, "chunk " + index + " failed its tag", e)
            } catch (e: javax.crypto.BadPaddingException) {
                throw ArchiveException(ArchiveRefusal.DAMAGED, "chunk " + index + " failed its tag", e)
            }
            pos = 0
            index++
            if (last) {
                finished = true
                pending = null
            } else {
                pending = readExactly(source, nextLen)
            }
            return true
        }

        private fun readLength(): Int? {
            val raw = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = source.read(raw, read, 4 - read)
                if (n == -1) {
                    if (read == 0) return null
                    throw ArchiveException(ArchiveRefusal.DAMAGED, "half a length field")
                }
                read += n
            }
            val len = getInt(raw, 0)
            if (len < 0 || len > MAX_CHUNK_BYTES + TAG_BYTES) {
                throw ArchiveException(ArchiveRefusal.DAMAGED, "chunk length " + len)
            }
            if (len != 0 && len > chunkBytes + TAG_BYTES) {
                throw ArchiveException(ArchiveRefusal.DAMAGED, "chunk longer than the archive declares")
            }
            return len
        }
    }

    // -- Small helpers --------------------------------------------------

    fun readExactly(source: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = source.read(out, read, n - read)
            if (got == -1) {
                throw ArchiveException(ArchiveRefusal.DAMAGED, "wanted " + n + " bytes, got " + read)
            }
            read += got
        }
        return out
    }

    fun readAll(source: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = source.read(buf)
            if (n == -1) break
            if (out.size() + n > limit) {
                throw ArchiveException(ArchiveRefusal.DAMAGED, "entry over " + limit + " bytes")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    private fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun putInt(out: ByteArray, offset: Int, value: Int): Int {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
        return offset + 4
    }

    private fun getInt(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xFF) shl 24) or
            ((raw[offset + 1].toInt() and 0xFF) shl 16) or
            ((raw[offset + 2].toInt() and 0xFF) shl 8) or
            (raw[offset + 3].toInt() and 0xFF)
}

/** Why an archive was refused. Each one is a different sentence to the user. */
enum class ArchiveRefusal {
    NOT_A_BACKUP,
    NEWER_FORMAT,
    NEWER_SCHEMA,
    WRONG_PASSWORD,
    DAMAGED
}

class ArchiveException(
    val refusal: ArchiveRefusal,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
