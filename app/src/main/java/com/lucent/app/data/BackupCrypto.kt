package com.lucent.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted backup file: **everything** inside it is encrypted, not just the API key.
 *
 * ### What changed and why
 *
 * The old backup was a plain `.json` in which only the API key was encrypted. Every note, every
 * task, every chat message, and every attachment sat in it in the clear. That is a strange thing for
 * a local-first app to produce, because a backup is the *one* artefact that deliberately leaves the
 * device — onto a cloud drive, into an email to yourself, down into a Downloads folder shared with
 * every app that asked for storage access. It is precisely the file that most needs encrypting, and
 * it was the only one that wasn't.
 *
 * Now the whole payload is sealed. The file is opaque; nothing readable is left outside the envelope
 * except the header needed to open it.
 *
 * ### Two modes, and why both exist
 *
 * | Mode | Key from | Restores on another device? | Protects against |
 * |---|---|---|---|
 * | `APP_KEY` (default) | A passphrase compiled into the app | **Yes, silently** | Anyone reading the file — a cloud sync, a file manager, a curious app |
 * | `PASSWORD` (opt-in) | A password **you** chose | Yes, if you type the password | Everyone, including someone holding both the file *and* the APK |
 *
 * `APP_KEY` is honest obfuscation, and it is the right default: the passphrase ships inside the APK,
 * so someone with both the backup and the APK can extract it. What it buys is that a backup sitting
 * in Google Drive is not a plaintext dump of your entire life, and that restoring on a new phone
 * requires nothing but the file. That is the trade every "no password" backup makes, and it should
 * be *said*, not implied.
 *
 * `PASSWORD` is real encryption. The key is derived from your password with PBKDF2 (a high iteration
 * count, so guessing is expensive), and it exists nowhere but in your head. The cost is exactly what
 * that sentence implies: **forget the password and the backup is gone forever.** There is no reset,
 * no recovery, no support email — that is not a bug in the design, it *is* the design, and an
 * encrypted backup with a back door would simply be an unencrypted backup with extra steps.
 *
 * ### Format
 *
 * ```
 *   MAGIC(8) "LCNTBAK1" | version(1) | mode(1) | iterations(4) | salt(16)
 *   then a FileCrypto stream (chunked AES-256-GCM) holding the JSON payload
 * ```
 *
 * The header is plaintext on purpose. Import has to know, *before* it can decrypt anything, whether
 * this file needs a password — otherwise the only way to find out would be to ask for one and see if
 * it worked, which is a miserable thing to do to someone restoring a backup.
 *
 * The payload is streamed through [FileCrypto], so a 500 MB backup full of photos never has to fit
 * in memory to be written or read.
 */
object BackupCrypto {

    private val MAGIC = "LCNTBAK1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1

    private const val SALT_LEN = 16
    private const val KEY_BITS = 256
    private const val HEADER_LEN = 8 + 1 + 1 + 4 + SALT_LEN // 30

    /**
     * PBKDF2 rounds for a **user password**. Deliberately expensive: this is the only thing standing
     * between a stolen backup and its contents, so each guess an attacker makes should cost real
     * time. ~0.5–1s on a mid-range phone, paid once per export and once per import — a price the
     * user pays in full and an attacker pays per guess.
     */
    const val PASSWORD_ITERATIONS = 210_000

    /**
     * Rounds for the built-in key. Low on purpose, and it costs nothing to be honest about why:
     * the passphrase is *inside the APK*, so an attacker doesn't guess it, they read it. Spending a
     * second stretching a key that isn't secret would be pure theatre — it would slow the user down
     * and inconvenience nobody else.
     */
    private const val APP_KEY_ITERATIONS = 10_000

    private val APP_PASSPHRASE = "Lucent-backup-passphrase-v2".toCharArray()

    private val random = SecureRandom()

    enum class Mode(val id: Byte) {
        APP_KEY(0),
        PASSWORD(1);

        companion object {
            fun fromId(id: Byte): Mode? = entries.firstOrNull { it.id == id }
        }
    }

    /** What an import needs to know before it can even try: is this ours, and does it want a password? */
    data class Header(val mode: Mode, val iterations: Int, val salt: ByteArray) {
        val needsPassword: Boolean get() = mode == Mode.PASSWORD

        // Data classes compare ByteArray by identity, which would silently make two identical
        // headers unequal. Overridden so this behaves the way anyone reading the call site expects.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return mode == other.mode && iterations == other.iterations && salt.contentEquals(other.salt)
        }

        override fun hashCode(): Int =
            (mode.hashCode() * 31 + iterations) * 31 + salt.contentHashCode()
    }

    // -----------------------------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------------------------

    /**
     * True when [bytes] is one of our encrypted backups.
     *
     * Import checks for our envelope header and refuses anything else — legacy ZIP/JSON formats are
     * no longer read (see `BackupManager.inspect`).
     */
    fun looksEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_LEN && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Read the plaintext header. Returns null if this isn't one of ours. */
    fun readHeader(bytes: ByteArray): Header? {
        if (!looksEncrypted(bytes)) return null
        if (bytes[MAGIC.size] != VERSION) return null
        val mode = Mode.fromId(bytes[MAGIC.size + 1]) ?: return null
        var p = MAGIC.size + 2
        val iterations = ((bytes[p].toInt() and 0xFF) shl 24) or
            ((bytes[p + 1].toInt() and 0xFF) shl 16) or
            ((bytes[p + 2].toInt() and 0xFF) shl 8) or
            (bytes[p + 3].toInt() and 0xFF)
        p += 4
        if (iterations <= 0 || bytes.size < p + SALT_LEN) return null
        val salt = bytes.copyOfRange(p, p + SALT_LEN)
        return Header(mode, iterations, salt)
    }

    // -----------------------------------------------------------------------------------------
    // Key derivation
    // -----------------------------------------------------------------------------------------

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        // Rust-accelerated PBKDF2 when available (see nativebridge/LucentNative): identical bytes
        // out, several times faster in — which matters most right here, where a password-protected
        // export/import pays 210,000 rounds. The JCE path below is the unchanged fallback.
        com.lucent.app.nativebridge.LucentNative
            .pbkdf2Sha256(passphrase, salt, iterations, KEY_BITS / 8)
            ?.let { return SecretKeySpec(it, "AES") }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun keyFor(header: Header, password: String?): SecretKey = when (header.mode) {
        Mode.APP_KEY -> deriveKey(APP_PASSPHRASE, header.salt, header.iterations)
        Mode.PASSWORD -> {
            require(!password.isNullOrEmpty()) { "This backup needs a password" }
            deriveKey(password.toCharArray(), header.salt, header.iterations)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------------------------

    /**
     * Open [out] for writing an encrypted backup, returning a stream that encrypts everything
     * written to it. A blank or null [password] selects [Mode.APP_KEY].
     *
     * **The returned stream must be closed** — that is when the final frame is sealed. `use { }`
     * does this; forgetting to would produce a file that import correctly refuses as truncated,
     * which is the right failure but an annoying one to debug, so: close it.
     */
    fun encryptingStream(out: OutputStream, password: String?): OutputStream {
        val usePassword = !password.isNullOrEmpty()
        val mode = if (usePassword) Mode.PASSWORD else Mode.APP_KEY
        val iterations = if (usePassword) PASSWORD_ITERATIONS else APP_KEY_ITERATIONS
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }

        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(mode.id.toInt())
        out.write(iterations ushr 24)
        out.write(iterations ushr 16)
        out.write(iterations ushr 8)
        out.write(iterations)
        out.write(salt)

        val key = keyFor(Header(mode, iterations, salt), password)
        return FileCrypto.encryptingStream(out, key)
    }

    fun encrypt(payload: ByteArray, password: String?): ByteArray {
        val buffer = ByteArrayOutputStream(payload.size + 128)
        encryptingStream(buffer, password).use { it.write(payload) }
        return buffer.toByteArray()
    }

    // -----------------------------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------------------------

    /**
     * Decrypt a backup produced by [encrypt].
     *
     * Throws [WrongPasswordException] when the password is wrong (or missing for a password-mode
     * file). That distinction matters: "wrong password, try again" and "this file is damaged" call
     * for completely different things from the person restoring, and collapsing them into one
     * generic failure is how you get someone to throw away a perfectly good backup.
     */
    fun decrypt(bytes: ByteArray, password: String?): ByteArray {
        val header = readHeader(bytes) ?: throw IOException("Not a Lucent backup")
        if (header.needsPassword && password.isNullOrEmpty()) throw WrongPasswordException()

        val key = keyFor(header, password)
        val body = bytes.inputStream()
        // Skip the plaintext header we've already parsed.
        var skipped = 0L
        while (skipped < HEADER_LEN) {
            val n = body.skip(HEADER_LEN - skipped)
            if (n <= 0) throw IOException("Backup file is truncated")
            skipped += n
        }

        return try {
            FileCrypto.decryptingStream(body, key).use { it.readBytes() }
        } catch (t: Throwable) {
            // A GCM tag failure on the very first frame is, overwhelmingly, a wrong password —
            // a damaged file is far rarer than a typo. Report the likely cause, not the literal one.
            if (header.needsPassword) throw WrongPasswordException() else throw IOException("Backup file is damaged", t)
        }
    }

    /** Streamed variant, for restoring a large backup without holding it twice in memory. */
    fun decryptingStream(input: InputStream, header: Header, password: String?): InputStream {
        if (header.needsPassword && password.isNullOrEmpty()) throw WrongPasswordException()
        return FileCrypto.decryptingStream(input, keyFor(header, password))
    }

    class WrongPasswordException : IOException("Wrong backup password")
}
