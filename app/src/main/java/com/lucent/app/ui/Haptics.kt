package com.lucent.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext

/**
 * Tactile feedback for valid interactions — a short vibration when the user does something that
 * actually performs a function (copy, save, send, confirm, pick), not merely tapping empty space.
 *
 * We drive the vibrator motor directly (rather than View.performHapticFeedback) so the tick fires
 * consistently across devices and even where system haptics for a given feedback constant are
 * suppressed. A very short, low-amplitude one-shot keeps it subtle. Requires no permission:
 * VIBRATE is a normal permission but the platform doesn't prompt for it, and we degrade silently
 * if the device has no vibrator.
 */
object Haptics {

    // Duration/strength of the standard "something happened" tick.
    private const val TICK_MS = 18L
    private const val TICK_AMPLITUDE = 90 // out of 255; gentle

    // Typewriter feedback (issue 11). A barely-there tick as each character is revealed, and a
    // firm buzz when the whole reply lands.
    //
    // "Lowest intensity for every character" is honoured as literally as a phone motor usefully
    // allows: the per-character effect is the shortest, weakest one-shot the API permits. But a
    // vibrator physically cannot restart faster than a few milliseconds, and firing one every ~20ms
    // for a long reply would fuse into a single unpleasant buzz and drain the battery — so
    // [typingTick] throttles itself to at most one pulse per [TYPING_MIN_GAP_MS]. In practice the
    // typewriter's own cadence is close to that anyway, so you feel one faint tap per character or
    // two, not a drill.
    private const val TYPING_TICK_MS = 8L
    private const val TYPING_AMPLITUDE = 1        // the floor; the gentlest a motor will do
    private const val TYPING_MIN_GAP_MS = 16L

    // The single strong pulse that marks "the reply is complete".
    private const val FINISH_MS = 42L
    private const val FINISH_AMPLITUDE = 235      // out of 255; deliberately assertive

    @Volatile private var lastTypingTickAt = 0L

    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun oneShot(context: Context, ms: Long, amplitude: Int) {
        val vib = vibrator(context.applicationContext) ?: return
        if (!vib.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (vib.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                vib.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Throwable) {
            // Some OEM vibrators throw under odd states; feedback is non-essential, so swallow it.
        }
    }

    /** A short confirmation tick. Safe to call from any thread; no-op if there's no vibrator. */
    fun tick(context: Context) = oneShot(context, TICK_MS, TICK_AMPLITUDE)

    /**
     * The faint per-character typewriter tick. Self-throttling (see [TYPING_MIN_GAP_MS]) so calling
     * it on every revealed glyph is safe and won't turn into a continuous buzz. Any-thread safe.
     */
    fun typingTick(context: Context) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTypingTickAt < TYPING_MIN_GAP_MS) return
        lastTypingTickAt = now
        oneShot(context, TYPING_TICK_MS, TYPING_AMPLITUDE)
    }

    /** The single, firm "reply finished" buzz. Any-thread safe. */
    fun finishBuzz(context: Context) = oneShot(context, FINISH_MS, FINISH_AMPLITUDE)
}

/**
 * Like [Modifier.clickable] but fires a haptic tick before running [onClick]. Use this for taps
 * that perform a real action (buttons, cards that open something, list rows) so every meaningful
 * tap gives tactile feedback. Purely decorative taps should keep plain `clickable`.
 */
fun Modifier.hapticClickable(onClick: () -> Unit): Modifier = composed {
    val context = LocalContext.current
    clickable {
        Haptics.tick(context)
        onClick()
    }
}
