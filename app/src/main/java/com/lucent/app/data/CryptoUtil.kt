package com.lucent.app.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable encryption for the API key stored inside a backup file.
 *
 * The previous implementation bound the key to this device's AndroidKeyStore, which
 * meant a backup could never be decrypted on another device or even after reinstalling
 * the app (the keystore entry is wiped). This version derives the AES key from an
 * app-embedded passphrase plus a random salt (PBKDF2), and stores salt + IV alongside
 * the ciphertext, so the same backup file decrypts anywhere the app runs.
 *
 * Note on threat model: because the passphrase ships inside the app, this protects the
 * key from casually appearing in plaintext in the exported JSON, not from a determined
 * attacker who has both the file and the APK. That is the right trade-off for a personal
 * backup file that still needs to be restorable across installs.
 */
object CryptoUtil {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 10_000
    private const val KEY_BITS = 256
    private val PASSPHRASE = "Lucent-backup-passphrase-v1".toCharArray()

    // Rust acceleration (see nativebridge/LucentNative + rust/src/lib.rs). PBKDF2 and AES-GCM are
    // standardized primitives, so the Rust and JCE paths produce byte-identical output for the same
    // input; whenever the native library is absent (unit tests, an unbundled ABI) or a call fails,
    // the original javax.crypto code below runs instead. Format and behaviour are unchanged either
    // way — only the time spent deriving is.
    private fun deriveKeyBytes(salt: ByteArray): ByteArray {
        com.lucent.app.nativebridge.LucentNative
            .pbkdf2Sha256(PASSPHRASE, salt, PBKDF2_ITERATIONS, KEY_BITS / 8)
            ?.let { return it }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(PASSPHRASE, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return factory.generateSecret(spec).encoded
    }

    // Output layout (Base64, NO_WRAP): [ salt(16) | iv(12) | ciphertext+tag ]
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val keyBytes = deriveKeyBytes(salt)
        val plain = plainText.toByteArray(Charsets.UTF_8)
        val encrypted = com.lucent.app.nativebridge.LucentNative
            .aesGcmSeal(keyBytes, iv, ByteArray(0), plain)
            ?: run {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(plain)
            }
        return Base64.encodeToString(salt + iv + encrypted, Base64.NO_WRAP)
    }

    // Returns "" if the data can't be decrypted (corrupt, or produced by the old
    // keystore-based scheme) instead of throwing — callers treat "" as "no key".
    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return try {
            val combined = Base64.decode(cipherText, Base64.NO_WRAP)
            if (combined.size <= SALT_LENGTH + IV_LENGTH) return ""
            val salt = combined.copyOfRange(0, SALT_LENGTH)
            val iv = combined.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val encrypted = combined.copyOfRange(SALT_LENGTH + IV_LENGTH, combined.size)
            val keyBytes = deriveKeyBytes(salt)
            val plain = com.lucent.app.nativebridge.LucentNative
                .aesGcmOpen(keyBytes, iv, ByteArray(0), encrypted)
                ?: run {
                    // Native unavailable (or its tag check failed) → the original Cipher path,
                    // whose own tag verification yields the identical accept/reject decision.
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                    cipher.doFinal(encrypted)
                }
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
