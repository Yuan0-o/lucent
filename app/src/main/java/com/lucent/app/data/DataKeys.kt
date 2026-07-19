package com.lucent.app.data

import android.content.Context
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The two data-encryption keys the app holds on this device: one for attachment files, one for the
 * database.
 *
 * ### How a key is protected
 *
 * Each key is 32 random bytes, generated once and stored in a small file in the app's *internal*
 * storage, wrapped by [LocalSecrets] — which means AES-GCM under a key held by the **Android
 * Keystore**, where the platform owns the key material and the app itself cannot export it. Lifting
 * `keys/attachments.key` off the phone gets you nothing; the bytes only mean something on the device
 * that made them.
 *
 * ### Why not derive the key from a user password
 *
 * Because it would mean typing a password every time the app opens, and an app you must unlock to
 * jot down a phone number is an app you stop using. That is a real feature (an app lock), but it's a
 * *different* feature, and pretending a background encryption key is one would give the worst of
 * both: the friction of a password, none of the guarantees, since the key would have to be cached
 * somewhere anyway.
 *
 * ### The thing you must know
 *
 * At-rest encryption means that if a key is *truly* lost, the data it protects is gone — that is
 * what encryption is, and every encrypted notes app shares the property. The realistic ways to lose
 * a key are uninstalling the app, clearing its data, or a factory reset — in all of which the data
 * is deleted anyway, so nothing is actually at stake.
 *
 * The one genuinely asymmetric case used to be **Keystore corruption**: the files survive but the
 * Keystore-held wrapping key doesn't, so the data key becomes unreadable and the notes are lost even
 * though every ciphertext byte is intact. That case is now defended. Each data key is stored twice —
 * once wrapped by the Keystore (strong, non-exportable) and once by a device-bound but
 * Keystore-*independent* scheme ([RecoverableSecret]) — so a dead Keystore falls back to the
 * recovery copy and recovers the identical key. See [getOrCreate].
 *
 * A backup remains the answer to the *other* failures — a dropped phone, an uninstall, a factory
 * reset — and, because the backup inlines attachment *bytes* as plaintext inside its encrypted
 * envelope (see BackupManager), a restore on a brand-new device reconstructs everything without
 * needing any key from the old one. The backup is encrypted with a portable scheme precisely so it
 * does not depend on any single device's Keystore.
 *
 * ### Fallback
 *
 * If the Keystore is unavailable when a key is first created, [LocalSecrets] transparently falls back
 * to its portable scheme and marks the stored value accordingly. The app keeps working and the data
 * is still not plaintext — it is merely obfuscated rather than device-bound. Degrading one notch beats
 * refusing to start, and the marker means the file still decrypts once the Keystore recovers.
 */
object DataKeys {

    private const val KEY_DIR = "keys"
    private const val ATTACHMENT_KEY_FILE = "attachments.key"
    private const val DATABASE_KEY_FILE = "database.key"
    // Recovery copies live next to the primaries, suffixed. Same 32 bytes, wrapped by a
    // Keystore-INDEPENDENT scheme (see RecoverableSecret) so a dead Keystore can't strand the data.
    private const val RECOVERY_SUFFIX = ".recovery"
    private const val KEY_BYTES = 32 // AES-256

    private val lock = Any()
    @Volatile private var attachmentKey: SecretKey? = null
    @Volatile private var databaseKeyHex: String? = null

    private fun keyDir(context: Context): File =
        File(context.applicationContext.filesDir, KEY_DIR).apply { if (!exists()) mkdirs() }

    /** Decode a Base64 key string to exactly [KEY_BYTES] bytes, or null if it isn't that. */
    private fun decodeKey(base64: String): ByteArray? {
        if (base64.isEmpty()) return null
        val bytes = try {
            android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
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

    /**
     * Read the raw key bytes from [fileName], creating them on first use.
     *
     * ### Two wrappings, so one dead lock can't lose the data
     *
     * Every data key is stored twice: once wrapped by the Android Keystore ([LocalSecrets], strong
     * and non-exportable) and once wrapped by a device-bound but Keystore-*independent* scheme
     * ([RecoverableSecret]). Reads try the Keystore copy first; if it fails — the exact "Keystore
     * invalidated while the files survive" case that used to be unrecoverable — the recovery copy
     * yields the identical bytes and the database and attachments open normally.
     *
     * ### Self-healing
     *
     * If the Keystore copy is unreadable but the recovery copy works, the Keystore copy is rewritten
     * from the recovered bytes. A transient Keystore hiccup therefore repairs itself on the next
     * launch instead of leaving the app permanently on the fallback path.
     *
     * ### Never destructive
     *
     * If *neither* copy can be read, the files are left exactly as they are (never overwritten with a
     * fresh key, which would silently orphan everything they protect) and the caller is told, so it
     * can degrade rather than the data being quietly discarded.
     *
     * The writes are atomic — temp file then rename — because a key file half-written when the
     * process dies is indistinguishable from a corrupted one, and a corrupted key file means the
     * data it protects is unreadable.
     */
    private fun getOrCreate(context: Context, fileName: String): ByteArray {
        val appContext = context.applicationContext
        val file = File(keyDir(appContext), fileName)
        val recoveryFile = File(keyDir(appContext), fileName + RECOVERY_SUFFIX)

        if (file.exists() || recoveryFile.exists()) {
            // 1. Primary: the Keystore-wrapped copy.
            val primaryBytes = if (file.exists()) {
                val stored = try { file.readText() } catch (t: Throwable) { "" }
                decodeKey(LocalSecrets.decrypt(stored))
            } else null

            if (primaryBytes != null) {
                // Keep the recovery copy present AND current. It's created for older installs that
                // never had one, and — importantly — rewritten if it can no longer be opened with
                // this device's material (e.g. ANDROID_ID changed). Refreshing it here, while the
                // Keystore still hands us the key, is what stops a later Keystore failure from
                // meeting an already-stale recovery copy and losing the data. Cheap and best-effort.
                val recoveryStale = !recoveryFile.exists() || run {
                    val existing = try { recoveryFile.readText() } catch (t: Throwable) { "" }
                    !RecoverableSecret.canDecrypt(appContext, existing)
                }
                if (recoveryStale) writeRecoveryCopy(appContext, recoveryFile, primaryBytes)
                return primaryBytes
            }

            // 2. Fallback: the Keystore copy is gone or unreadable. Try the recovery copy.
            val recoveryBytes = if (recoveryFile.exists()) {
                val storedRecovery = try { recoveryFile.readText() } catch (t: Throwable) { "" }
                decodeKey(RecoverableSecret.decrypt(appContext, storedRecovery))
            } else null

            if (recoveryBytes != null) {
                // Heal the Keystore copy so a transient failure doesn't strand us on the fallback —
                // but only if the re-wrap actually succeeded. If the Keystore is fully dead,
                // LocalSecrets.encrypt returns "", and overwriting the primary with that would be
                // pointless churn; we simply keep serving from the recovery copy until the Keystore
                // recovers. Never destructive either way (the recovery copy is untouched).
                val rewrapped = LocalSecrets.encrypt(
                    android.util.Base64.encodeToString(recoveryBytes, android.util.Base64.NO_WRAP)
                )
                if (rewrapped.isNotEmpty()) atomicWrite(file, rewrapped)
                return recoveryBytes
            }

            // 3. Neither copy is readable. Preserve the evidence; let the caller decide how to
            //    degrade. This is now genuinely rare: it needs BOTH the Keystore copy to fail AND
            //    the recovery copy to be unreadable (e.g. the device id changed), whereas before a
            //    single Keystore failure was enough.
            throw IllegalStateException(
                "The encryption key in $fileName could not be read by either the Keystore or the " +
                    "recovery path. Data protected by it cannot be decrypted on this device; " +
                    "restore from a backup."
            )
        }

        // Fresh install: mint a key and write BOTH wrappings before returning it.
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val freshBase64 = android.util.Base64.encodeToString(fresh, android.util.Base64.NO_WRAP)
        if (!atomicWrite(file, LocalSecrets.encrypt(freshBase64))) {
            throw IllegalStateException("Could not store the encryption key")
        }
        writeRecoveryCopy(appContext, recoveryFile, fresh)
        return fresh
    }

    /** Write the Keystore-independent recovery copy. Best-effort: failure just means no safety net. */
    private fun writeRecoveryCopy(context: Context, recoveryFile: File, keyBytes: ByteArray) {
        val base64 = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val wrapped = RecoverableSecret.encrypt(context, base64)
        if (wrapped.isNotEmpty()) atomicWrite(recoveryFile, wrapped)
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

    /**
     * The database passphrase, in SQLCipher's raw-key form: `x'<64 hex chars>'`.
     *
     * The `x'...'` wrapper is not cosmetic. Handed a passphrase in that exact shape, SQLCipher
     * recognises it as **raw key material and skips its KDF entirely**. Given anything else it runs
     * PBKDF2 with 256,000 rounds on *every database open* — a quarter-second stall each launch, to
     * stretch a key that is already 32 bytes of `SecureRandom` and has nothing left to stretch. The
     * KDF exists to make a weak human password expensive to guess; spending it on a random key buys
     * precisely nothing and costs the user a visible pause every time they open the app.
     */
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
