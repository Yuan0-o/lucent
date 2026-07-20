package com.lucent.app.data

import android.content.Context
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop twin of the Android DataKeys: the two data-encryption keys (attachments, database),
 * each 32 random bytes, stored under `keys/` wrapped by [LocalSecrets].
 *
 * The Android build stores each key twice (Keystore-wrapped + a Keystore-independent recovery
 * copy) because the Android Keystore can die independently of the files. The desktop wrapping has
 * no such external dependency — [LocalSecrets]' master key lives in the very same directory — so a
 * single wrapped copy carries the same guarantees the two copies do on Android: as long as the
 * `keys/` directory survives, the data opens; if the whole profile is lost, the `.lcb` backup is
 * the recovery path on both platforms.
 *
 * Public API is identical to Android's so shared code compiles unchanged.
 */
object DataKeys {

    private const val KEY_DIR = "keys"
    private const val ATTACHMENT_KEY_FILE = "attachments.key"
    private const val DATABASE_KEY_FILE = "database.key"
    private const val KEY_BYTES = 32 // AES-256

    private val lock = Any()
    @Volatile private var attachmentKey: SecretKey? = null
    @Volatile private var databaseKeyHex: String? = null

    private fun keyDir(context: Context): File =
        File(context.applicationContext.filesDir, KEY_DIR).apply { if (!exists()) mkdirs() }

    private fun decodeKey(base64: String): ByteArray? {
        if (base64.isEmpty()) return null
        val bytes = try {
            java.util.Base64.getDecoder().decode(base64)
        } catch (t: Throwable) {
            null
        }
        return if (bytes != null && bytes.size == KEY_BYTES) bytes else null
    }

    /** Atomic write: temp file then rename, so a half-written key file can never be observed. */
    private fun atomicWrite(file: File, contents: String): Boolean {
        val temp = File(file.parentFile, "${file.name}.tmp")
        return try {
            temp.writeText(contents)
            if (temp.renameTo(file)) true else { temp.delete(); false }
        } catch (t: Throwable) {
            temp.delete()
            false
        }
    }

    private fun getOrCreate(context: Context, fileName: String): ByteArray {
        val file = File(keyDir(context), fileName)
        if (file.exists()) {
            val stored = try { file.readText() } catch (t: Throwable) { "" }
            decodeKey(LocalSecrets.decrypt(stored))?.let { return it }
            // Never overwrite an unreadable key with a fresh one — that would silently orphan
            // everything it protects. Same policy as Android.
            throw IllegalStateException(
                "The encryption key in $fileName could not be read. Data protected by it cannot " +
                    "be decrypted on this machine; restore from a backup."
            )
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val freshBase64 = java.util.Base64.getEncoder().encodeToString(fresh)
        if (!atomicWrite(file, LocalSecrets.encrypt(freshBase64))) {
            throw IllegalStateException("Could not store the encryption key")
        }
        return fresh
    }

    /** The AES key protecting attachment files on disk. */
    fun attachmentKey(context: Context): SecretKey {
        attachmentKey?.let { return it }
        synchronized(lock) {
            attachmentKey?.let { return it }
            val key = SecretKeySpec(getOrCreate(context, ATTACHMENT_KEY_FILE), "AES")
            attachmentKey = key
            return key
        }
    }

    /** The database passphrase in SQLCipher's raw-key form: `x'<64 hex chars>'`. */
    fun databasePassphrase(context: Context): String {
        databaseKeyHex?.let { return it }
        synchronized(lock) {
            databaseKeyHex?.let { return it }
            val bytes = getOrCreate(context, DATABASE_KEY_FILE)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            val passphrase = "x'$hex'"
            databaseKeyHex = passphrase
            return passphrase
        }
    }

    /** Whether a database key already exists — i.e. whether this install has ever been encrypted. */
    fun hasDatabaseKey(context: Context): Boolean =
        File(keyDir(context.applicationContext), DATABASE_KEY_FILE).exists()

    /** Test seam: forget the cached keys so the next call re-reads them from disk. */
    fun resetCacheForTesting() {
        synchronized(lock) {
            attachmentKey = null
            databaseKeyHex = null
        }
    }
}
