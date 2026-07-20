// Desktop compatibility shim for android.util.Base64, backed by java.util.Base64.
//
// Byte-compatible on purpose: the backup format, the API-key envelope (CryptoUtil), and the app-lock
// hashes all embed Base64 produced by this API on Android, so the desktop build must encode and
// decode exactly the same way for backups to travel between the two platforms.
package android.util

object Base64 {
    /** android.util.Base64 flag values, kept numerically identical to the Android constants. */
    const val DEFAULT = 0
    const val NO_WRAP = 2

    // Android's DEFAULT inserts line wraps every 76 chars when *encoding*; nothing in Lucent
    // encodes with DEFAULT (all call sites encode NO_WRAP), so a non-wrapping encoder is exact
    // for every value this app produces. The MIME decoder below accepts wrapped and unwrapped
    // input alike, which covers DEFAULT-decoding of legacy wrapped payloads too.
    fun encodeToString(input: ByteArray, flags: Int): String =
        java.util.Base64.getEncoder().encodeToString(input)

    fun decode(str: String, flags: Int): ByteArray {
        val cleaned = str.trim()
        return try {
            java.util.Base64.getDecoder().decode(cleaned)
        } catch (t: IllegalArgumentException) {
            // Legacy Android payloads may contain line breaks (DEFAULT flag); the MIME decoder
            // skips them, matching android.util.Base64.decode's tolerance.
            java.util.Base64.getMimeDecoder().decode(cleaned)
        }
    }
}
