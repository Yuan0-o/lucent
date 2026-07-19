package com.lucent.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for secrets that never leave this device — today, that means the API key and
 * the saved API profiles sitting in the DataStore preferences file.
 *
 * ### Why this exists alongside [CryptoUtil]
 *
 * The two look similar and are emphatically not interchangeable. The difference is who has to be
 * able to decrypt, and it's worth being precise about it, because using either one in the other's
 * place produces a bug that only shows up months later when someone restores a backup.
 *
 * [CryptoUtil] protects a **backup file**, which by definition has to be readable on *another*
 * device — a new phone, a reinstall — so its key is derived (PBKDF2) from a passphrase compiled
 * into the app. That makes the ciphertext portable. It also means the key ships inside the APK, so
 * that scheme is only ever obfuscation against a casual reader of the JSON, and it says so.
 *
 * This object protects data that must **never** be portable. The API key sat in `lucent_settings`
 * in plaintext: any process that could read the app's data directory — a backup extraction, an
 * `adb` pull from a debuggable build, a rooted device, a misconfigured cloud auto-backup — could
 * read it verbatim. Because that value only ever needs to be decrypted *right here*, the key can
 * live in the [AndroidKeyStore][KeyStore], where the private material is held by the platform and
 * cannot be exported by the app itself, let alone by anything that merely got hold of the file.
 * Copy the encrypted preferences to another device and it is inert.
 *
 * So the rule is simple: **on-device storage is Keystore-encrypted; anything written into a backup
 * file is [CryptoUtil]-encrypted.** [SettingsRepository] decrypts on read and [BackupManager] then
 * re-encrypts portably on the way out, which is why backups keep working across a reinstall while
 * the on-disk copy stays device-bound.
 *
 * ### Failure behaviour
 *
 * Keystore is available on every API level this app supports (26+), but it can still fail in the
 * wild: a corrupted keystore, an OEM with a broken StrongBox path, a key invalidated when the user
 * changes their lock screen. In all of those cases [encrypt] falls back to [CryptoUtil] and marks
 * the payload so [decrypt] knows which scheme produced it. The user keeps a working app with
 * obfuscated-not-plaintext secrets rather than an app that can't reach its own API key — degrading
 * one notch beats a hard failure on a value the user can always retype anyway.
 */
object LocalSecrets {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "lucent_local_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    // Every stored value carries a one-character scheme marker so decryption never has to guess,
    // and so a value written by the fallback path still decrypts after the keystore recovers.
    private const val PREFIX_KEYSTORE = "k1:"
    private const val PREFIX_PORTABLE = "p1:"

    private fun secretKey(): SecretKey? = try {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately NOT setUserAuthenticationRequired(true): the assistant has to be
                    // able to read the API key to make a request, including from a boot-time
                    // reschedule with the screen off. Requiring a fresh unlock would break the app
                    // for a threat model (an attacker holding an unlocked phone) that a locally
                    // stored key can't defend against anyway.
                    .build()
            )
        }.generateKey()
    } catch (t: Throwable) {
        null
    }

    /** Encrypt a value for storage on this device. Empty in, empty out. */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val key = secretKey() ?: return PREFIX_PORTABLE + CryptoUtil.encrypt(plainText)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            // GCM generates its own IV; read it back rather than supplying one, which is the
            // pattern AndroidKeyStore requires (it rejects caller-provided IVs for AES/GCM).
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            PREFIX_KEYSTORE + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (t: Throwable) {
            PREFIX_PORTABLE + CryptoUtil.encrypt(plainText)
        }
    }

    /**
     * Decrypt a value produced by [encrypt].
     *
     * Anything without a recognised prefix is assumed to be a **legacy plaintext value** and is
     * returned as-is. That is what silently upgrades an existing install: the old plaintext API key
     * is read back correctly on first launch, and [SettingsRepository] re-writes it encrypted the
     * next time it's saved — no migration step, no user-visible event, no chance of locking anyone
     * out of their own key.
     *
     * Returns "" when a genuinely encrypted value can't be decrypted (keystore key invalidated by a
     * lock-screen change, say). Callers treat "" as "no key set", which surfaces as an empty field
     * the user can retype — the only recoverable outcome.
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return when {
            stored.startsWith(PREFIX_KEYSTORE) -> {
                val key = secretKey() ?: return ""
                try {
                    val combined = Base64.decode(stored.removePrefix(PREFIX_KEYSTORE), Base64.NO_WRAP)
                    if (combined.size <= IV_LENGTH) return ""
                    val iv = combined.copyOfRange(0, IV_LENGTH)
                    val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    String(cipher.doFinal(encrypted), Charsets.UTF_8)
                } catch (t: Throwable) {
                    ""
                }
            }
            stored.startsWith(PREFIX_PORTABLE) -> CryptoUtil.decrypt(stored.removePrefix(PREFIX_PORTABLE))
            // Legacy plaintext, written before this class existed.
            else -> stored
        }
    }
}
