package com.lucent.app.nativebridge

/**
 * The bridge to `liblucent_native.so` — the Rust rewrite of the app's CPU-heavy primitives
 * (task: rewrite necessary parts in a higher-performance language without changing any logic).
 *
 * ### The contract that makes the rewrite safe
 *
 * Nothing in the app *depends* on this library. Every caller keeps its original Kotlin/JCE
 * implementation and consults it whenever the answer here is "unavailable":
 *
 *  - the library failed to load (unbundled ABI, stripped build, unit tests on a plain JVM),
 *  - a call returned null/false (which a Rust panic is converted into, never a crash).
 *
 * And because the accelerated functions are *standardized primitives* — PBKDF2-HMAC-SHA256 and
 * AES-256-GCM produce bit-identical output for identical input regardless of implementation, and
 * the blob math reproduces the exact FluidGlassBackground formulas — swapping the engine changes
 * timings only. Formats, files, backups, and animations are indistinguishable, which is precisely
 * the "not a single piece of logic or functionality changes" requirement. The Rust crate's own
 * test suite (`rust/src/lib.rs`) pins this with independently-generated reference vectors.
 */
object LucentNative {

    /** True when the Rust library is loaded and its calls may be used. */
    // Desktop adaptation: load through NativeLoader (resource-extracted DLL); the Kotlin/JCE
    // fallbacks below make a missing library invisible except in timings.
    val available: Boolean = NativeLoader.load("lucent_native")

    // ---- Crypto ----

    /**
     * PBKDF2-HMAC-SHA256 in Rust, or null → caller falls back to `SecretKeyFactory`.
     * [password] is the UTF-8 encoding of the passphrase chars — exactly what Android's
     * `PBKDF2WithHmacSHA256` uses internally, so derived keys are byte-identical.
     */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLenBytes: Int): ByteArray? {
        if (!available) return null
        return try {
            nativePbkdf2Sha256(password, salt, iterations, keyLenBytes)
        } catch (t: Throwable) {
            null
        }
    }

    /** Convenience for the app's char[]-shaped call sites; encodes as UTF-8 like the JCE does. */
    fun pbkdf2Sha256(password: CharArray, salt: ByteArray, iterations: Int, keyLenBytes: Int): ByteArray? =
        pbkdf2Sha256(String(password).toByteArray(Charsets.UTF_8), salt, iterations, keyLenBytes)

    /**
     * AES-256-GCM seal → `ciphertext || tag(16)` (identical to `AES/GCM/NoPadding` doFinal),
     * or null → caller falls back to `Cipher`.
     */
    fun aesGcmSeal(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray? {
        if (!available) return null
        return try {
            nativeAesGcmSeal(key, iv, aad, plaintext)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * AES-256-GCM open of `ciphertext || tag`. Returns the plaintext, or null when the tag does
     * not verify **or** the native path is unavailable. Callers must treat null as "use the
     * Cipher fallback", whose own tag check then produces the exact same failure the app always
     * had — so an attacker can never downgrade a tag failure into silent acceptance.
     */
    fun aesGcmOpen(key: ByteArray, iv: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (!available) return null
        return try {
            nativeAesGcmOpen(key, iv, aad, sealed)
        } catch (t: Throwable) {
            null
        }
    }

    // ---- Background animation ----

    /**
     * Fill [out] (size ≥ 36) with the six blobs' per-frame draw parameters
     * `[cx, cy, radius, corner, squash, angleDeg] × 6` at elapsed time [tMs].
     * Returns false → caller computes the same values with its original Kotlin math.
     */
    fun blobFrame(tMs: Float, width: Float, height: Float, out: FloatArray): Boolean {
        if (!available || out.size < 36) return false
        return try {
            nativeBlobFrame(tMs, width, height, out)
        } catch (t: Throwable) {
            false
        }
    }

    // ---- Native surface (rust/src/lib.rs) ----
    private external fun nativePbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray?
    private external fun nativeAesGcmSeal(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray?
    private external fun nativeAesGcmOpen(key: ByteArray, iv: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?
    private external fun nativeBlobFrame(tMs: Float, width: Float, height: Float, out: FloatArray): Boolean
}
