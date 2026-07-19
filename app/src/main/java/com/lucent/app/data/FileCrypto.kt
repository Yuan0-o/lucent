package com.lucent.app.data

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Streaming, authenticated encryption for files on disk — used for attachments and for the backup
 * payload.
 *
 * ### Why not just AES-GCM the whole file
 *
 * Because attachments can be enormous. A single-shot `Cipher.doFinal(file.readBytes())` needs the
 * entire plaintext *and* the entire ciphertext in memory at once, so a 300 MB video would be 600 MB
 * of heap and an immediate OOM on a phone. And `CipherInputStream` — the obvious streaming answer —
 * is a well-known trap with GCM: several JDK/Android versions swallow the `AEADBadTagException`
 * thrown at the end of the stream, which means a *tampered file decrypts silently into garbage*
 * instead of failing. An authenticated cipher whose authentication can be silently skipped is not
 * an authenticated cipher.
 *
 * So the file is split into 64 KiB frames, each independently sealed with AES-256-GCM. Memory use
 * is one frame, whatever the file size, and every frame's tag is checked as it is read — a
 * corrupted or tampered frame throws immediately rather than at some later `close()` nobody checks.
 *
 * ### Format
 *
 * ```
 *   header   MAGIC(8) | version(1) | baseNonce(8)
 *   frame    final(1) | length(4, big-endian) | ciphertext+tag(length)
 *   ...
 * ```
 *
 * Each frame's nonce is `baseNonce(8) || counter(4)`, so no nonce is ever reused under one key —
 * the fatal mistake with GCM, and the reason the counter is part of the nonce rather than being
 * left to chance.
 *
 * The `final` flag is *plaintext* (the reader must know before it decrypts) but it is fed into the
 * frame's AAD, so it is still authenticated: flipping it fails the tag check. That is what makes
 * **truncation** detectable. Without it, an attacker — or a half-finished write interrupted by a
 * dead battery — could lop the tail off a file and every remaining frame would still verify
 * perfectly, handing back a plausible, silently incomplete note. A stream that ends before a final
 * frame is an error, and this says so.
 *
 * ### Why the key is a parameter
 *
 * Nothing here reaches for the Android Keystore. The caller passes the key in, which keeps this
 * file pure `javax.crypto` — so it runs, and is *tested*, on a plain JVM. Crypto that can only be
 * exercised on a device is crypto that never gets exercised.
 */
object FileCrypto {

    /** Eight bytes, so a plaintext file cannot plausibly be mistaken for an encrypted one. */
    private val MAGIC = "LCNTCRY1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1

    private const val NONCE_PREFIX_LEN = 8
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16

    /** Plaintext bytes per frame. 64 KiB keeps peak memory trivial and framing overhead ~0.03%. */
    const val CHUNK = 64 * 1024

    private const val HEADER_LEN = 8 + 1 + NONCE_PREFIX_LEN // 17
    private const val FRAME_HEADER_LEN = 1 + 4              // 5

    private val random = SecureRandom()

    // -----------------------------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------------------------

    /**
     * Whether [file] carries our header.
     *
     * This is what lets encryption arrive without a migration: an attachment written by an older
     * build is plaintext, has no magic, and is simply read as-is. Files are re-encrypted lazily as
     * they're rewritten, and swept in the background at startup — but nothing ever *has* to be
     * converted for the app to keep working, so there is no big-bang conversion step to get wrong.
     */
    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER_LEN) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(MAGIC.size + 1)
                if (input.read(head) != head.size) return false
                head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) && head[MAGIC.size] == VERSION
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * The plaintext size of an encrypted file, derived from its length rather than by decrypting it.
     *
     * Used only by the 800 MB attachment cap, which sums thousands of files and must stay cheap —
     * decrypting every attachment to measure it would turn opening Settings into a stall. The result
     * is exact whenever the last frame is full and at most 20 bytes over otherwise, which against an
     * 800 MB ceiling is not a rounding error worth paying for.
     */
    fun plaintextSizeOf(file: File): Long {
        val total = file.length()
        if (total <= HEADER_LEN) return 0
        val body = total - HEADER_LEN
        val perFrame = (FRAME_HEADER_LEN + GCM_TAG_BYTES).toLong()
        // Each frame carries CHUNK plaintext bytes plus a fixed overhead; solve for the frame count.
        val frames = ((body + CHUNK + perFrame - 1) / (CHUNK + perFrame)).coerceAtLeast(1)
        return (body - frames * perFrame).coerceAtLeast(0)
    }

    // -----------------------------------------------------------------------------------------
    // Streams
    // -----------------------------------------------------------------------------------------

    /**
     * Wrap [out] so everything written to it is encrypted. **The returned stream must be closed** —
     * that's when the final frame (and its end-of-stream marker) is written. A stream that is never
     * closed produces a file that will be correctly rejected as truncated.
     */
    fun encryptingStream(out: OutputStream, key: SecretKey): OutputStream {
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { random.nextBytes(it) }
        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(noncePrefix)
        return EncryptingOutputStream(out, key, noncePrefix)
    }

    /** Wrap [input] so reads come back decrypted. Throws [IOException] on a bad tag or truncation. */
    fun decryptingStream(input: InputStream, key: SecretKey): InputStream {
        val head = ByteArray(HEADER_LEN)
        readFully(input, head, head.size)
        if (!head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || head[MAGIC.size] != VERSION) {
            throw IOException("Not a Lucent-encrypted stream")
        }
        val noncePrefix = head.copyOfRange(MAGIC.size + 1, HEADER_LEN)
        return DecryptingInputStream(input, key, noncePrefix)
    }

    // -----------------------------------------------------------------------------------------
    // Convenience
    // -----------------------------------------------------------------------------------------

    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val buffer = ByteArrayOutputStream(plain.size + 64)
        encryptingStream(buffer, key).use { it.write(plain) }
        return buffer.toByteArray()
    }

    fun decrypt(cipherText: ByteArray, key: SecretKey): ByteArray =
        decryptingStream(cipherText.inputStream(), key).use { it.readBytes() }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun nonceFor(prefix: ByteArray, counter: Int): ByteArray {
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_LEN)
        nonce[8] = (counter ushr 24).toByte()
        nonce[9] = (counter ushr 16).toByte()
        nonce[10] = (counter ushr 8).toByte()
        nonce[11] = counter.toByte()
        return nonce
    }

    // ---- Frame primitives, Rust-accelerated (see nativebridge/LucentNative) ----
    //
    // AES-256-GCM is a standardized primitive: the Rust engine and javax.crypto produce
    // byte-identical frames for identical (key, nonce, aad, data), so which one ran is
    // undetectable in the file. The Cipher path below is kept verbatim and used whenever the
    // native library is absent (plain-JVM unit tests, an unbundled ABI), whenever a key's raw
    // bytes aren't extractable, or if a native call fails — behaviour is unchanged in every case.

    private fun sealFrame(key: SecretKey, nonce: ByteArray, aad: ByteArray, plain: ByteArray, len: Int): ByteArray {
        key.encoded?.let { raw ->
            val exact = if (len == plain.size) plain else plain.copyOfRange(0, len)
            com.lucent.app.nativebridge.LucentNative.aesGcmSeal(raw, nonce, aad, exact)?.let { return it }
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain, 0, len)
    }

    private fun openFrame(key: SecretKey, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        key.encoded?.let { raw ->
            com.lucent.app.nativebridge.LucentNative.aesGcmOpen(raw, nonce, aad, sealed)?.let { return it }
            // Null from the native open means EITHER "library unavailable" or "tag failed";
            // both fall through to Cipher, whose own tag check reproduces the exact original
            // accept-or-throw decision — a forged frame is still always rejected.
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed)
    }

    /** AAD binds the frame's position *and* its end-of-stream flag to its tag. */
    private fun aadFor(counter: Int, isFinal: Boolean) = byteArrayOf(
        if (isFinal) 1 else 0,
        (counter ushr 24).toByte(),
        (counter ushr 16).toByte(),
        (counter ushr 8).toByte(),
        counter.toByte()
    )

    private fun readFully(input: InputStream, buffer: ByteArray, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buffer, read, len - read)
            if (n < 0) throw EOFException("Encrypted stream ended early — the file is truncated")
            read += n
        }
    }

    private class EncryptingOutputStream(
        out: OutputStream,
        private val key: SecretKey,
        private val noncePrefix: ByteArray
    ) : FilterOutputStream(out) {

        private val buffer = ByteArray(CHUNK)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val take = minOf(remaining, CHUNK - filled)
                System.arraycopy(b, offset, buffer, filled, take)
                filled += take
                offset += take
                remaining -= take
                // Only flush a *full* frame here. The last frame is written by close(), because
                // only close() knows it is the last — which is the entire point of the final flag.
                if (filled == CHUNK) writeFrame(isFinal = false)
            }
        }

        private fun writeFrame(isFinal: Boolean) {
            val sealed = sealFrame(key, nonceFor(noncePrefix, counter), aadFor(counter, isFinal), buffer, filled)

            out.write(if (isFinal) 1 else 0)
            out.write(sealed.size ushr 24)
            out.write(sealed.size ushr 16)
            out.write(sealed.size ushr 8)
            out.write(sealed.size)
            out.write(sealed)

            filled = 0
            counter++
        }

        override fun close() {
            if (closed) return
            closed = true
            // Always emit a final frame, even for an empty file — an empty payload still has to be
            // distinguishable from a file that was truncated to nothing.
            writeFrame(isFinal = true)
            out.flush()
            out.close()
        }
    }

    private class DecryptingInputStream(
        input: InputStream,
        private val key: SecretKey,
        private val noncePrefix: ByteArray
    ) : FilterInputStream(input) {

        private var plain = ByteArray(0)
        private var offset = 0
        private var counter = 0
        private var sawFinal = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (offset >= plain.size) {
                if (sawFinal) return -1
                if (!readFrame()) return -1
                // A final frame may legitimately be empty (an empty file). Signal EOF, not a hang.
                if (plain.isEmpty()) return -1
            }
            val take = minOf(len, plain.size - offset)
            System.arraycopy(plain, offset, b, off, take)
            offset += take
            return take
        }

        /** Read and authenticate one frame. Returns false at a clean end of stream. */
        private fun readFrame(): Boolean {
            val flag = `in`.read()
            if (flag < 0) {
                // The stream ran out without ever presenting a final frame. Refusing here is the
                // whole reason the flag exists: silently returning what we already decoded would
                // hand the caller a convincing, incomplete file.
                throw IOException("Encrypted stream is truncated — no end-of-stream frame")
            }
            val isFinal = flag == 1

            val lenBytes = ByteArray(4)
            readFully(`in`, lenBytes, 4)
            val length = ((lenBytes[0].toInt() and 0xFF) shl 24) or
                ((lenBytes[1].toInt() and 0xFF) shl 16) or
                ((lenBytes[2].toInt() and 0xFF) shl 8) or
                (lenBytes[3].toInt() and 0xFF)
            // A frame can never exceed one chunk plus its tag. Rejecting anything larger stops a
            // corrupt length field from provoking a multi-gigabyte allocation.
            if (length < GCM_TAG_BYTES || length > CHUNK + GCM_TAG_BYTES) {
                throw IOException("Encrypted stream is corrupt — implausible frame length")
            }

            val sealed = ByteArray(length)
            readFully(`in`, sealed, length)

            plain = try {
                openFrame(key, nonceFor(noncePrefix, counter), aadFor(counter, isFinal), sealed)
            } catch (t: Throwable) {
                // Wrong key, tampered bytes, or a reordered frame. All of them mean the same thing
                // to the caller: this data cannot be trusted, and must not be handed back.
                throw IOException("Could not decrypt — wrong key, or the file has been altered", t)
            }
            offset = 0
            counter++
            sawFinal = isFinal
            return true
        }
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
