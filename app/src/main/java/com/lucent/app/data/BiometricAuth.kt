package com.lucent.app.data

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper over AndroidX [BiometricPrompt] for the App Lock's optional biometric unlock.
 *
 * Mirrors the desktop Windows Hello design on purpose: strictly opt-in, only ever surfaced when the
 * device actually has usable biometrics, and it never becomes the only way in — the password path is
 * always present, and every failure or cancellation falls back to it silently rather than locking
 * the user out. Nothing here throws to the caller.
 */
object BiometricAuth {

    // BIOMETRIC_WEAK covers fingerprint and most face unlock across the widest range of devices,
    // which is the right bar for "unlock this app" (as opposed to releasing a keystore key, which
    // would demand BIOMETRIC_STRONG). No CryptoObject is bound — a success simply clears the lock.
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** True only when the hardware exists AND at least one biometric is enrolled and ready to use. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Show the system biometric sheet. All callbacks are optional. [onError] fires only for real
     * errors worth surfacing; a user cancel or "use password" tap resolves silently, because the
     * password field is right there to fall back to.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
        onFailed: () -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancellations are expected and silent — the password field is still available.
                    val silent = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    if (!silent) onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // A non-matching finger/face: the sheet stays up for a retry, we just signal it.
                    onFailed()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            prompt.authenticate(info)
        } catch (t: Throwable) {
            // Never let prompt construction/launch crash the caller; fall back to password.
            onError(t.message ?: "")
        }
    }
}
