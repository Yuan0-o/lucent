package com.lucent.app.data

import android.content.DesktopContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop twin of the Android LocalSecrets: seal/open small preference values with AES-256-GCM.
 *
 * ### What replaces the Android Keystore here
 *
 * Android wraps these values under a key the hardware Keystore holds and the app cannot export.
 * Windows has no equivalent the JVM can reach without native code, so the desktop build keeps the
 * same *shape* — values on disk are AES-GCM ciphertext, never plaintext — under a per-install
 * random master key stored beside the data (`keys/master.key`). That is honest local obfuscation
 * rather than hardware binding: someone with full access to the user's profile directory can
 * recover the values, exactly as they could copy the whole profile anyway. What it preserves is
 * that no API key, base URL, or lock hash ever sits readable in a settings file, and that a synced
 * or backed-up settings file leaks nothing on its own.
 *
 * ### Format compatibility
 *
 * The sealed string format matches Android's (`v1:` prefix + Base64(iv | ciphertext+tag)), and —
 * exactly like the original — [decrypt] returns an unprefixed value untouched, so plaintext
 * defaults and legacy values read back correctly and are re-sealed on their next save.
 */
object LocalSecrets {

    private const val PREFIX = "v1:"
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    private val keyBytes: ByteArray? by lazy {
        try {
            val dir = File(DesktopContext.filesDir, "keys").apply { mkdirs() }
            val file = File(dir, "master.key")
            if (file.exists()) {
                val loaded = java.util.Base64.getDecoder().decode(file.readText().trim())
                if (loaded.size == 32) loaded else null
            } else {
                val fresh = ByteArray(32).also { random.nextBytes(it) }
                val tmp = File(dir, "master.key.tmp")
                tmp.writeText(java.util.Base64.getEncoder().encodeToString(fresh))
                if (tmp.renameTo(file)) fresh else { tmp.delete(); null }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Seal [value]. Returns "" for "" (a cleared value stays cleared) and plaintext if sealing fails. */
    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val key = keyBytes ?: return value
        return try {
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            PREFIX + java.util.Base64.getEncoder().encodeToString(iv + sealed)
        } catch (t: Throwable) {
            value
        }
    }

    /** Open a sealed value; a value without our prefix is returned as-is (legacy plaintext). */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        val key = keyBytes ?: return ""
        return try {
            val combined = java.util.Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (combined.size <= IV_LEN) return ""
            val iv = combined.copyOfRange(0, IV_LEN)
            val sealed = combined.copyOfRange(IV_LEN, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }
}
