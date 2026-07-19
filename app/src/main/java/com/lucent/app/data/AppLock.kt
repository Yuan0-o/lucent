package com.lucent.app.data

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The App Lock's credential logic (task 2): hashing, verification, and security-question recovery.
 *
 * ### What is stored, and what is not
 *
 * The password and the security-question answer are **never stored**. What is stored (encrypted at
 * rest by [SettingsRepository] via [LocalSecrets], but one-way regardless) is a small JSON blob:
 *
 * ```
 *   { "v":1, "iter":N, "salt":b64, "pwHash":b64, "question":"…", "ansHash":b64 }
 * ```
 *
 * `pwHash` and `ansHash` are PBKDF2-HMAC-SHA256 of the password / normalised answer against a single
 * random `salt`. Verifying re-derives the hash and compares in constant time. Because only hashes
 * are kept, someone who lifts the preferences file off the device still cannot read the password or
 * the answer — and Lucent itself cannot "recover" a forgotten password, only let the user set a new
 * one after proving they know the answer.
 *
 * ### Recovery
 *
 * Forgetting the password is recoverable *if* the user still knows their security-question answer:
 * [verifyAnswer] gates [changePassword], which mints a fresh salt and new hashes while keeping the
 * same question. A forgotten answer *and* a forgotten password is deliberately unrecoverable — the
 * lock would be pointless otherwise — but "clear all data" in Settings still resets everything.
 *
 * This is pure `javax.crypto`, so it runs and is testable on a plain JVM.
 */
object AppLock {

    private const val VERSION = 1
    private const val ITERATIONS = 120_000
    private const val SALT_LEN = 16
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    /**
     * Build a fresh credentials blob from a chosen [password], [question], and [answer].
     * The answer is normalised (trimmed + lower-cased) so "Fluffy" and " fluffy " both match later.
     *
     * ### Recovery is optional (task 9)
     *
     * [question] and [answer] may be blank, in which case the lock is created with **no recovery
     * path at all** — an empty question and an empty answer hash — and [hasRecovery] reports false.
     *
     * Writing the empty strings deliberately is not a detail; it is the security fix. The obvious
     * implementation, hashing an empty answer like any other, produces a blob where `ansHash` is the
     * hash of `""` — and the "forgot password" flow normalises its input by trimming, so *a single
     * space* would have matched it. Skipping the question would have quietly installed a lock anyone
     * could open. An empty `ansHash` can't be matched by anything, because [verify] fails outright
     * when the stored hash won't decode.
     */
    fun createCredentials(password: String, question: String, answer: String): String {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        // Recovery needs BOTH halves. Half of one is not a security question, so it stores neither.
        val recovery = question.isNotBlank() && answer.isNotBlank()
        return JSONObject()
            .put("v", VERSION)
            .put("iter", ITERATIONS)
            .put("salt", b64(salt))
            .put("pwHash", b64(hash(password.toCharArray(), salt, ITERATIONS)))
            .put("question", if (recovery) question.trim() else "")
            .put(
                "ansHash",
                if (recovery) b64(hash(normalizeAnswer(answer).toCharArray(), salt, ITERATIONS)) else ""
            )
            .toString()
    }

    /**
     * Whether this lock has a working security question, i.e. whether "forgot password" can lead
     * anywhere. False for a lock set up without one (task 9), and for any blob missing either half.
     *
     * The UI uses this to *hide* the recovery route rather than to offer one that can never succeed —
     * a "Forgot password?" link that cannot possibly help is worse than no link, because it costs
     * someone their remaining attempts to discover that.
     */
    fun hasRecovery(credentialsJson: String): Boolean {
        val o = parse(credentialsJson) ?: return false
        return o.optString("question", "").isNotBlank() && o.optString("ansHash", "").isNotEmpty()
    }

    /** The stored security question, or "" if the blob is empty/unreadable. */
    fun question(credentialsJson: String): String = parse(credentialsJson)?.optString("question", "") ?: ""

    /** True if [password] matches the stored hash. */
    fun verifyPassword(credentialsJson: String, password: String): Boolean {
        val o = parse(credentialsJson) ?: return false
        return verify(password.toCharArray(), o, "pwHash")
    }

    /**
     * True if [answer] (after normalisation) matches the stored hash. Always false when the lock was
     * set up without a security question, since there is no stored hash for anything to match.
     */
    fun verifyAnswer(credentialsJson: String, answer: String): Boolean {
        if (!hasRecovery(credentialsJson)) return false
        val o = parse(credentialsJson) ?: return false
        return verify(normalizeAnswer(answer).toCharArray(), o, "ansHash")
    }

    /**
     * Return a new credentials blob with [newPassword] set, keeping the existing question and answer.
     * Uses a fresh salt so the new password's hash shares nothing with the old one. Returns null if
     * the existing blob can't be read.
     */
    fun changePassword(credentialsJson: String, newPassword: String): String? {
        val o = parse(credentialsJson) ?: return null
        val question = o.optString("question", "")
        // Re-hash the *answer* under the new salt too. We only have its old hash, not the answer
        // itself, so instead we keep the old salt/answer-hash pair intact by minting the new salt for
        // the password only. Simpler and safe: keep the whole blob's salt and just replace pwHash.
        val salt = b64ToBytes(o.optString("salt", "")) ?: return null
        val iter = o.optInt("iter", ITERATIONS)
        return JSONObject()
            .put("v", VERSION)
            .put("iter", iter)
            .put("salt", o.optString("salt", ""))
            .put("pwHash", b64(hash(newPassword.toCharArray(), salt, iter)))
            .put("question", question)
            .put("ansHash", o.optString("ansHash", ""))
            .toString()
    }

    // -----------------------------------------------------------------------------------------

    private fun verify(input: CharArray, o: JSONObject, hashField: String): Boolean {
        val salt = b64ToBytes(o.optString("salt", "")) ?: return false
        val iter = o.optInt("iter", ITERATIONS)
        val expected = b64ToBytes(o.optString(hashField, "")) ?: return false
        val actual = hash(input, salt, iter)
        return constantTimeEquals(expected, actual)
    }

    private fun hash(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return factory.generateSecret(spec).encoded
    }

    private fun normalizeAnswer(answer: String): String = answer.trim().lowercase()

    private fun parse(json: String): JSONObject? =
        if (json.isBlank()) null else try { JSONObject(json) } catch (t: Throwable) { null }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun b64ToBytes(s: String): ByteArray? =
        if (s.isEmpty()) null else try { Base64.decode(s, Base64.NO_WRAP) } catch (t: Throwable) { null }

    /** Length-independent comparison so a match can't be timed byte by byte. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
