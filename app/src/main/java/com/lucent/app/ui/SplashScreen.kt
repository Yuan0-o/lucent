package com.lucent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Startup readiness, shared between [com.lucent.app.MainActivity] and whatever is waiting on it.
 *
 * Exactly one flag, and it exists because building the encrypted database is expensive enough that
 * it cannot happen on the main thread, yet the screens all ask for it the moment they compose. The
 * background build flips this when the database is genuinely open, and composition of the heavy
 * content waits for it — which is what keeps the first frame free to be the splash, and only the
 * splash.
 *
 * A plain global rather than anything cleverer: it is process-scoped state with exactly one writer,
 * and it must survive an Activity recreation (the database does).
 */
object AppReady {
    var databaseReady by mutableStateOf(false)
}

/**
 * The launch animation: two hands wave hello, then turn into liquid glass.
 *
 * ### What this is actually for
 *
 * Cold start spends a moment doing real work — reading the saved theme, palette, font and lock state
 * from disk *before* the first frame (so the app never flashes the wrong colours), then composing a
 * large UI. Until now that moment was a flat, empty window: nothing was wrong, but nothing said so
 * either, and a blank screen always reads as "stuck" rather than "starting".
 *
 * So this fills it with something. Crucially it fills it *without adding to it*: the splash is drawn
 * **over** the real app, which composes underneath at the same time. The waiting that was already
 * happening now happens behind an animation, and by the time the hands fade the app behind it is
 * built and ready.
 *
 * ### The animation
 *
 * About seven and a half seconds, in four movements:
 *
 *  1. **Arrive** (0–0.7s) — the hands pop in with a slight overshoot, the way something alive enters.
 *  2. **Wave** (0.7–3.3s) — both hands swing about their wrists, a cheeky little "hello"; they mirror
 *     each other so the greeting reads as two-handed and symmetric rather than a single hand waving.
 *  3. **Become glass** (3.3–6.6s) — the solid hands cross-fade into the app's own material: a
 *     translucent pane tinted by the live palette, a bright rim, and a specular highlight that sweeps
 *     across them as they change. It also *wobbles* — squashing and stretching, strongest at
 *     the midpoint and settling to nothing — which is what makes it read as having briefly turned to
 *     liquid rather than simply having faded. This is the part worth lingering on, so it gets the
 *     largest share of the (now longer) running time.
 *  4. **Leave** (6.6–7.7s) — it floats up and dissolves, and the app is already there behind it.
 *
 * The hands are drawn with plain Canvas primitives — no image asset, no vector drawable — so they are a
 * few hundred bytes of code, scales to any screen without a folder of densities, and can be morphed
 * arbitrarily, which is the entire trick in movement 3.
 *
 * ### Getting out of it
 *
 * Seven-odd seconds is generous the first time and long the hundredth, so **the top-right "Skip"
 * control, or a back press, finishes it immediately** — and the app behind is already composed, so
 * skipping is instant rather than a shortcut into more waiting. A stray tap anywhere else does
 * nothing, so the animation can't be cut short by accident. Completion is also driven by a plain
 * `delay`, not by the frame clock that drives the drawing: if that clock were ever starved the hands
 * would freeze, and a frozen splash that never ends is an app that never starts. The animation may
 * stall; the launch may not.
 */
@Composable
fun LucentSplash(
    paletteColors: List<Color>,
    backdropColor: Color,
    onFinished: () -> Unit,
    // Whether the drifting blob background animates behind the hands. Passed straight through to
    // [FluidGlassBackground] so the splash obeys the SAME "drifting background" setting the app
    // does: off in Settings means off here too, from the very first frame — never blobs during the
    // hands and stillness after it.
    backgroundAnimated: Boolean = true
) {
    val onGradient = LocalOnGradient.current
    val inspection = LocalInspectionMode.current

    // Guards against the tap, the back press and the timer all racing to finish the same splash.
    var done by remember { mutableStateOf(false) }
    fun finish() {
        if (!done) {
            done = true
            onFinished()
        }
    }

    // Elapsed milliseconds, from a single frame clock — the same approach the drifting background
    // uses, and for the same reason: one snapshot read per frame instead of a dozen animation objects.
    var elapsed by remember { mutableFloatStateOf(0f) }
    if (!inspection) {
        LaunchedEffect(Unit) {
            val totalNanos = (TOTAL_MS * 1_000_000f).toLong()
            val start = withInfiniteAnimationFrameNanos { it }
            var now = start
            while (now - start < totalNanos) {
                now = withInfiniteAnimationFrameNanos { it }
                elapsed = (now - start) / 1_000_000f
            }
        }
    }

    // The authority on when the splash ends. Deliberately independent of the frame clock above.
    LaunchedEffect(Unit) {
        if (inspection) return@LaunchedEffect
        delay(TOTAL_MS.toLong())
        finish()
    }

    BackHandler(enabled = true) { finish() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Swallows every touch so nothing lands on the app composing underneath. It no longer
            // *finishes* on tap, though: skipping is now an explicit control (top-right), so a stray
            // tap can't cut the animation short by accident.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { })
            }
    ) {
        // The same living background the app itself uses, so the splash is the app arriving rather
        // than a separate screen shown in front of it — including whether it drifts at all, which
        // follows the user's "drifting background" setting exactly like the app behind it.
        FluidGlassBackground(
            palette = paletteColors,
            backdropColor = backdropColor,
            animated = backgroundAnimated,
            modifier = Modifier.fillMaxSize()
        )

        val t = elapsed
        // ---- Phase envelopes ----
        // Entrance: 0.6 -> 1.0 scale with a small overshoot, plus a fade in.
        val enter = (t / ENTER_MS).coerceIn(0f, 1f)
        val enterEased = 1f - (1f - enter) * (1f - enter)                   // ease-out
        val overshoot = sin(enter * PI.toFloat()) * 0.06f                    // brief bulge past 1.0
        val scaleIn = 0.62f + 0.38f * enterEased + overshoot

        // Wave: a few swings, tapering off as the morph begins.
        val waveT = ((t - ENTER_MS) / (MORPH_START_MS - ENTER_MS)).coerceIn(0f, 1f)
        val waveAngle = sin(waveT * WAVE_CYCLES * 2f * PI.toFloat()) * 24f * (1f - waveT * 0.35f)

        // Morph: 0 = solid hands, 1 = glass hands.
        val glass = ((t - MORPH_START_MS) / (MORPH_END_MS - MORPH_START_MS)).coerceIn(0f, 1f)
        val glassEased = glass * glass * (3f - 2f * glass)
        // Liquid wobble: strongest halfway through the change, nothing at either end.
        val wobble = sin(glass * PI.toFloat()) * 0.055f * sin(t / 90f)

        // Exit: float up and dissolve.
        val exit = ((t - EXIT_START_MS) / (TOTAL_MS - EXIT_START_MS)).coerceIn(0f, 1f)
        val exitEased = exit * exit
        val alpha = (1f - exitEased).coerceIn(0f, 1f) * enterEased.coerceAtLeast(0.001f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val unit = size.minDimension / 410f
            val cx = size.width / 2f
            val cy = size.height / 2f - 30f * unit - exitEased * 90f * unit

            withTransform({
                translate(left = cx, top = cy)
                scale(
                    scaleX = unit * scaleIn * (1f + wobble),
                    scaleY = unit * scaleIn * (1f - wobble),
                    pivot = Offset.Zero
                )
                // Both hands swing about their own wrists — a symmetric, two-handed "hello", rather
                // than the whole drawing rocking with the wave.
            }) {
                drawHands(
                    glass = glassEased,
                    alpha = alpha,
                    waveAngle = waveAngle,
                    tint = paletteColors.firstOrNull() ?: Color.White
                )
            }
        }

        // The wordmark arrives with the glass, so the two land together.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 190.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Lucent",
                color = onGradient.copy(alpha = glassEased * (1f - exitEased) * 0.95f),
                fontSize = 30.sp,
                textAlign = TextAlign.Center
            )
        }

        // Skip control: a small line of text in the top-right, present from the very first frame (its
        // alpha does not depend on the animation's progress, only fading out as the splash itself
        // leaves) and tappable to end the splash at once. This replaces "tap anywhere to skip" — it is
        // deliberate and discoverable, and a stray tap elsewhere no longer cuts the animation short.
        Text(
            text = com.lucent.app.i18n.S.skipAnimation,
            color = onGradient.copy(alpha = (1f - exitEased) * 0.6f),
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 10.dp, end = 18.dp)
                .clickable { finish() }
                .padding(6.dp)
        )
    }
}

/**
 * Draws two waving hands at the origin, in a space roughly 200 units wide.
 *
 * Each hand is a rounded palm with four fingers, a thumb and a little sleeve cuff, built from plain
 * Canvas primitives — no image asset, no vector drawable — so it scales to any screen and can be
 * morphed arbitrarily, which is the entire trick in the "become glass" phase.
 *
 * The two hands are mirror images and swing about their own wrists in opposite directions, so they
 * read as a symmetric, two-handed "hello" rather than a single hand or two hands swaying in lockstep.
 *
 * [glass] cross-fades between the drawn hands (0) and the glass ones (1) — the same shapes both
 * times, so nothing jumps, only the material. [waveAngle] swings each hand about its wrist, and
 * [tint] is the live palette colour the glass picks up.
 */
private fun DrawScope.drawHands(
    glass: Float,
    alpha: Float,
    waveAngle: Float,
    tint: Color
) {
    if (alpha <= 0.001f) return

    val solid = (1f - glass) * alpha
    val glassy = glass * alpha

    val fur = Color(0xFFFFE9D6)   // warm palm colour
    val line = Color(0xFF7A6560)  // soft "drawn" outline
    val cuff = Color(0xFFFF9E7A)  // sleeve band at the wrist

    // Two hands. hx = wrist x; dir mirrors the wave so they swing together; mx mirrors the geometry
    // so both thumbs point outward. Fingers are near-vertical, so mx barely moves them; it mostly
    // decides which side the thumb sits on.
    for (hand in 0..1) {
        val hx = if (hand == 0) -58f else 58f
        val dir = if (hand == 0) 1f else -1f
        val mx = if (hand == 0) 1f else -1f
        val palmLeft = hx - 26f   // palm is symmetric, so this is the same regardless of mx

        withTransform({ rotate(degrees = waveAngle * dir, pivot = Offset(hx, 78f)) }) {
            // ---- Sleeve cuff at the wrist ----
            if (solid > 0.002f) {
                drawRoundRect(
                    color = cuff.copy(alpha = solid),
                    topLeft = Offset(hx - 24f, 58f),
                    size = Size(48f, 26f),
                    cornerRadius = CornerRadius(11f, 11f)
                )
                drawRoundRect(
                    color = line.copy(alpha = solid),
                    topLeft = Offset(hx - 24f, 58f),
                    size = Size(48f, 26f),
                    cornerRadius = CornerRadius(11f, 11f),
                    style = Stroke(width = 3f)
                )
            }

            // ---- Fingers: four outlined capsules fanning up from the palm top ----
            // Each capsule is an outline (a hair thicker) with a fill on top, for the "drawn line" look.
            val fingers = listOf(
                floatArrayOf(-15f, 6f, -18f, -38f),  // index
                floatArrayOf(-5f, 4f, -6f, -52f),    // middle (longest)
                floatArrayOf(6f, 4f, 7f, -48f),      // ring
                floatArrayOf(15f, 8f, 18f, -32f)     // little
            )
            for (fdef in fingers) {
                val a = Offset(hx + mx * fdef[0], fdef[1])
                val b = Offset(hx + mx * fdef[2], fdef[3])
                if (solid > 0.002f) {
                    drawLine(line.copy(alpha = solid), a, b, 14.2f, StrokeCap.Round)
                    drawLine(fur.copy(alpha = solid), a, b, 11f, StrokeCap.Round)
                }
                if (glassy > 0.002f) {
                    drawLine(Color.White.copy(alpha = glassy * 0.7f), a, b, 14.2f, StrokeCap.Round)
                    drawLine(tint.copy(alpha = glassy * 0.20f), a, b, 11f, StrokeCap.Round)
                }
            }

            // ---- Thumb: an outlined capsule out to the (outer) side ----
            run {
                val a = Offset(hx + mx * -20f, 36f)
                val b = Offset(hx + mx * -44f, 12f)
                if (solid > 0.002f) {
                    drawLine(line.copy(alpha = solid), a, b, 15.2f, StrokeCap.Round)
                    drawLine(fur.copy(alpha = solid), a, b, 12f, StrokeCap.Round)
                }
                if (glassy > 0.002f) {
                    drawLine(Color.White.copy(alpha = glassy * 0.7f), a, b, 15.2f, StrokeCap.Round)
                    drawLine(tint.copy(alpha = glassy * 0.20f), a, b, 12f, StrokeCap.Round)
                }
            }

            // ---- Palm: a rounded rectangle, drawn last so it sits cleanly over the finger roots ----
            val palmTL = Offset(palmLeft, 2f)
            val palmSize = Size(52f, 62f)
            val palmR = CornerRadius(24f, 24f)
            if (solid > 0.002f) {
                drawRoundRect(fur.copy(alpha = solid), palmTL, palmSize, palmR)
                drawRoundRect(line.copy(alpha = solid), palmTL, palmSize, palmR, style = Stroke(width = 3.2f))
            }
            if (glassy > 0.002f) {
                drawRoundRect(Color.White.copy(alpha = glassy * 0.16f), palmTL, palmSize, palmR)
                drawRoundRect(tint.copy(alpha = glassy * 0.20f), palmTL, palmSize, palmR)
                drawRoundRect(Color.White.copy(alpha = glassy * 0.75f), palmTL, palmSize, palmR, style = Stroke(width = 2.8f))
                // A soft top-left sheen so the glass palm catches the light.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = glassy * 0.28f),
                        0.6f to Color.Transparent
                    ),
                    topLeft = palmTL,
                    size = palmSize,
                    cornerRadius = palmR
                )
            }
        }
    }
}

// ---- Timings, in milliseconds ----
// One place to retune the whole sequence; every phase above is expressed against these rather than
// against hard-coded numbers scattered through the drawing code. Lengthened to roughly 1.5x of the
// original, with the glass transformation given proportionally more of that time (it is the part
// worth lingering on).
// E6: overall launch-animation speed. A factor below 1 makes the whole sequence quicker; every
// duration below is multiplied by it, so the relative timing of the phases is preserved exactly.
private const val SPEED = 0.85f
private const val TOTAL_MS = 7700f * SPEED
private const val ENTER_MS = 700f * SPEED
private const val MORPH_START_MS = 3300f * SPEED
private const val MORPH_END_MS = 6600f * SPEED
private const val EXIT_START_MS = 6600f * SPEED
private const val WAVE_CYCLES = 4.5f // a count of wave swings, not a duration — left unscaled
