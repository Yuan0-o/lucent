package com.lucent.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A second, **Keystore-independent** wrapping for the two irreplaceable data keys (the database key
 * and the attachment key).
 *
 * ### The single point of failure this removes
 *
 * The primary protection for those keys is [LocalSecrets], which wraps them with a key held in the
 * Android Keystore. That is strong — the wrapping key can't be exported, so lifting the key file off
 * the device is useless. But it has one genuinely dangerous failure mode, and it is not
 * hypothetical: on some OEM builds the Keystore key can be **invalidated or corrupted** (a lock
 * screen change, a broken StrongBox path, a firmware update) while the app's files sit perfectly
 * intact on disk. When that happens the Keystore-wrapped copy of the data key becomes permanently
 * unreadable — and because that key is what decrypts the SQLCipher database and every attachment,
 * the user's notes are gone even though every byte of the ciphertext survives. That asymmetric case
 * — files live, key dead — is the one failure a "keep a backup" answer addresses only if the user
 * happened to export recently, which most never do.
 *
 * This class is the escrow that closes it. Alongside the Keystore-wrapped copy, [DataKeys] also
 * stores the *same* 32 key bytes wrapped by a key derived here — one that does **not** depend on the
 * Keystore surviving. If the Keystore copy ever fails to unwrap, [DataKeys] falls back to this copy,
 * recovers the identical key, and the database and attachments open exactly as before. No data loss,
 * no restore required.
 *
 * ### Why this doesn't just re-open the hole the Keystore was closing
 *
 * The recovery key is derived (PBKDF2-HMAC-SHA256) from two inputs:
 *
 *  1. **A per-device, per-app installation id** — `Settings.Secure.ANDROID_ID`. On API 26+ this is
 *     scoped to the app's signing key *and* the device *and* the user, it is stable for the life of
 *     the install, and — crucially — it survives Keystore corruption. It is **reset** precisely in
 *     the cases where the data is legitimately gone anyway: a factory reset, or clearing the app's
 *     data. So a recovery key built on it can only ever recover keys on the same install that made
 *     them, which is exactly the scope we want.
 *  2. **A pepper compiled into the app.** This means the id alone isn't enough; you also need the
 *     APK. It is honest defence-in-depth, not a secret (it ships in the binary), and it is *said* to
 *     be, in the same spirit as the backup app-key.
 *
 * The security intent of the original design is preserved: copy the key file to *another* device and
 * it is still inert, because that device's `ANDROID_ID` is different and this device's is not
 * exportable through any app API. What changes is only that a dead Keystore on *this* device no
 * longer takes the data down with it — the failure mode that had no defence before.
 *
 * ### Testability
 *
 * The actual wrap/unwrap ([seal] / [open]) takes the device material as a **parameter** and never
 * reaches for Android, exactly as [FileCrypto] and [BackupCrypto] take their keys as parameters —
 * so the crypto that matters is exercised on a plain JVM in [CryptoTest] rather than only on a
 * device. [encrypt] / [decrypt] are the thin Android-facing wrappers that look the material up.
 *
 * The stored blob is self-describing: `salt(16) | iv(12) | ciphertext+tag`, Base64 (NO_WRAP). A
 * fresh salt and IV per write means two wrappings of the same key never collide and no GCM nonce is
 * ever reused under a derived key.
 */
object RecoverableSecret {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 120_000

    // Not a secret: it ships in the APK. Its only job is to make the ANDROID_ID insufficient on its
    // own, so recovering a key needs both the device id and this build. Versioned so the scheme can
    // change later without silently misinterpreting an old blob.
    private const val PEPPER = "Lucent-recovery-pepper-v1"

    private val random = SecureRandom()

    /**
     * The device-bound material this scheme keys from. Kept in one place so the Android wrappers can
     * never disagree about it.
     *
     * `ANDROID_ID` is the right identifier here — it is app+device+user scoped on modern Android and
     * outlives the Keystore. In the extraordinary case it comes back blank (some emulators, a
     * provisioning race), we fall back to a fixed token so the scheme still functions; the wrapping
     * is then only as device-bound as the pepper, which is strictly better than failing to store a
     * recovery copy at all.
     */
    @SuppressLint("HardwareIds")
    private fun deviceMaterial(context: Context): CharArray {
        val androidId = try {
            Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (t: Throwable) {
            null
        }
        val id = androidId?.takeIf { it.isNotBlank() } ?: "lucent-no-android-id"
        return material(id)
    }

    /** Combine the app pepper with a device id into the PBKDF2 password. Pure; used by tests too. */
    internal fun material(deviceId: String): CharArray = (PEPPER + '|' + deviceId).toCharArray()

    private fun deriveKey(passwordMaterial: CharArray, salt: ByteArray): SecretKey {
        // Rust-accelerated PBKDF2 when available (see nativebridge/LucentNative): identical key
        // bytes, so an escrow copy written by either engine opens under the other. This derivation
        // sits on the database-recovery path, where 120,000 rounds used to be pure JVM time.
        com.lucent.app.nativebridge.LucentNative
            .pbkdf2Sha256(passwordMaterial, salt, PBKDF2_ITERATIONS, KEY_BITS / 8)
            ?.let { return SecretKeySpec(it, "AES") }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passwordMaterial, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /**
     * Pure wrap: seal [plain] under a key derived from [passwordMaterial], returning the raw
     * `salt(16) | iv(12) | ciphertext+tag` bytes. No Android dependency (no `android.util.Base64`),
     * so this is the part [CryptoTest] exercises directly. Returns null on any failure.
     */
    internal fun seal(passwordMaterial: CharArray, plain: ByteArray): ByteArray? {
        return try {
            val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passwordMaterial, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            val sealed = cipher.doFinal(plain)
            salt + iv + sealed
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Pure unwrap: recover the bytes sealed by [seal], or null if the material is wrong or the blob
     * is corrupt. No Android dependency.
     */
    internal fun open(passwordMaterial: CharArray, blob: ByteArray): ByteArray? {
        if (blob.size <= SALT_LEN + IV_LEN) return null
        return try {
            val salt = blob.copyOfRange(0, SALT_LEN)
            val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
            val sealed = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passwordMaterial, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(sealed)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Wrap [plainBase64] (the Base64 of the raw key bytes) into a portable, device-bound blob, itself
     * Base64 for storage in a text key file. Returns "" only if the platform crypto is somehow
     * unavailable, in which case the caller simply skips writing a recovery copy — the Keystore copy
     * still exists, so this degrades to the old behaviour rather than breaking anything.
     */
    fun encrypt(context: Context, plainBase64: String): String {
        if (plainBase64.isEmpty()) return ""
        val blob = seal(deviceMaterial(context), plainBase64.toByteArray(Charsets.UTF_8)) ?: return ""
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /**
     * Recover the Base64 key string from a blob produced by [encrypt], or "" if it can't be read
     * (wrong device, corrupt blob). The caller treats "" as "no usable recovery copy" and moves on.
     */
    fun decrypt(context: Context, stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val plain = open(deviceMaterial(context), blob) ?: return ""
            String(plain, Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Whether [stored] can still be opened with *this device's current* material. Used to detect a
     * recovery copy that has gone stale — e.g. because `ANDROID_ID` changed — so the caller can
     * rewrite it while it still has the key from the (working) Keystore. Rewriting it then keeps the
     * recovery path viable for the day the Keystore itself fails, instead of both being dead at once.
     */
    fun canDecrypt(context: Context, stored: String): Boolean = decrypt(context, stored).isNotEmpty()
}
