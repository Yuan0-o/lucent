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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
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
 * The launch animation: a little cat waves hello, gives a playful blink, then turns to liquid glass.
 * (Desktop copy of the shared splash — identical to :app except it drops Android's status-bar inset.)
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
 * happening now happens behind an animation, and by the time the cat fades the app behind it is
 * built and ready.
 *
 * ### The animation
 *
 * About seven and a half seconds, in five movements:
 *
 *  1. **Arrive** (0–0.7s) — the cat pops in with a slight overshoot, the way something alive enters.
 *  2. **Wave** (0.7–2.5s) — its two round paws swing about where they join the body, a cheeky
 *     little "hello"; they mirror each other so the greeting reads as two-pawed and symmetric, and
 *     they settle back to rest at the end of the swing.
 *  3. **Blink** (2.6–3.2s) — the eyes squeeze shut into two happy little arcs and open again, a
 *     playful beat before the change.
 *  4. **Become glass** (3.3–6.6s) — the solid cat cross-fades into the app's own material: a
 *     translucent pane tinted by the live palette, a bright rim, and a specular highlight — while
 *     keeping its shape and face, so it is recognisably the *same* cat rendered in glass. It also
 *     *wobbles* — squashing and stretching, strongest at the midpoint and settling to nothing —
 *     which is what makes it read as having briefly turned to liquid rather than simply faded. This
 *     is the part worth lingering on, so it gets the largest share of the running time.
 *  5. **Leave** (6.6–7.7s) — it floats up and dissolves, and the app is already there behind it.
 *
 * The cat is drawn with plain Canvas primitives — no image asset, no vector drawable — so it is a
 * few hundred bytes of code, scales to any screen without a folder of densities, and can be morphed
 * arbitrarily, which is the entire trick in movement 4.
 *
 * ### Getting out of it
 *
 * Seven-odd seconds is generous the first time and long the hundredth, so **the top-right "Skip"
 * control, or a back press, finishes it immediately** — and the app behind is already composed, so
 * skipping is instant rather than a shortcut into more waiting. A stray tap anywhere else does
 * nothing, so the animation can't be cut short by accident. Completion is also driven by a plain
 * `delay`, not by the frame clock that drives the drawing: if that clock were ever starved the cat
 * would freeze, and a frozen splash that never ends is an app that never starts. The animation may
 * stall; the launch may not.
 */
@Composable
fun LucentSplash(
    paletteColors: List<Color>,
    backdropColor: Color,
    onFinished: () -> Unit,
    // Whether the drifting blob background animates behind the cat. Passed straight through to
    // [FluidGlassBackground] so the splash obeys the SAME "drifting background" setting the app
    // does: off in Settings means off here too, from the very first frame — never blobs during the
    // cat and stillness after it.
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

        // Wave: a few paw swings between the entrance and the blink. WAVE_CYCLES is a whole number so
        // the swing lands back at rest (angle 0) exactly when the wave window ends — the paws don't
        // freeze mid-lift before the blink.
        val waveT = ((t - ENTER_MS) / (WAVE_END_MS - ENTER_MS)).coerceIn(0f, 1f)
        val waveAngle = sin(waveT * WAVE_CYCLES * 2f * PI.toFloat()) * 22f * (1f - waveT * 0.25f)

        // Blink: 0 -> 1 -> 0 over the blink window (eyes shut, then open). A half-sine gives a smooth
        // close-and-open; it is 0 everywhere outside the window, so the eyes are wide open during the
        // wave and again during the glass morph.
        val blink = if (t in BLINK_START_MS..BLINK_END_MS) {
            val bt = (t - BLINK_START_MS) / (BLINK_END_MS - BLINK_START_MS)
            sin(bt * PI.toFloat())
        } else 0f

        // Morph: 0 = solid cat, 1 = glass cat.
        val glass = ((t - MORPH_START_MS) / (MORPH_END_MS - MORPH_START_MS)).coerceIn(0f, 1f)
        val glassEased = glass * glass * (3f - 2f * glass)
        // Liquid wobble: strongest halfway through the change, nothing at either end.
        val wobble = sin(glass * PI.toFloat()) * 0.055f * sin(t / 90f)

        // Exit: float up and dissolve.
        val exit = ((t - EXIT_START_MS) / (TOTAL_MS - EXIT_START_MS)).coerceIn(0f, 1f)
        val exitEased = exit * exit
        val alpha = (1f - exitEased).coerceIn(0f, 1f) * enterEased.coerceAtLeast(0.001f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val unit = size.minDimension / 480f
            val cx = size.width / 2f
            val cy = size.height / 2f - 30f * unit - exitEased * 90f * unit

            withTransform({
                translate(left = cx, top = cy)
                scale(
                    scaleX = unit * scaleIn * (1f + wobble),
                    scaleY = unit * scaleIn * (1f - wobble),
                    pivot = Offset.Zero
                )
                // Only the paws swing (about where they meet the body); the rest of the cat holds
                // still, so the wave reads as a greeting rather than the whole drawing rocking.
            }) {
                drawCat(
                    glass = glassEased,
                    alpha = alpha,
                    waveAngle = waveAngle,
                    blink = blink,
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
                .padding(top = 10.dp, end = 18.dp)
                .clickable { finish() }
                .padding(6.dp)
        )
    }
}

/**
 * Draws the cat at the origin, in a space roughly 240 units wide.
 *
 * The whole animal is plain Canvas primitives — rounded rects, ovals, arcs, lines and two small
 * paths (the ears and the tail) — so it scales to any screen and can be morphed arbitrarily, which
 * is the entire trick in the "become glass" phase. Everything is drawn back-to-front: tail, ears,
 * body, then the face, then the two paws on top.
 *
 * [glass] cross-fades between the drawn cat (0) and the glass one (1). The silhouette shapes (body,
 * ears, paws, tail) pick up a translucent pane, a tint and a bright rim; the face marks (eyes,
 * nose, mouth) and the belly keep a faint presence so the glass cat is still visibly *this* cat
 * rather than an anonymous pane. [waveAngle] swings each paw about where it meets the body, [blink]
 * (0 open, 1 shut) drives the eyes, and [tint] is the live palette colour the glass picks up.
 */
private fun DrawScope.drawCat(
    glass: Float,
    alpha: Float,
    waveAngle: Float,
    blink: Float,
    tint: Color
) {
    if (alpha <= 0.001f) return

    // Alpha bands. `sol` fades the solid drawing out as glass comes in; `gl` fades the glass in.
    // `faceA`/`bellyA`/`beanA` never drop all the way to 0 while glassy, so the face, belly and paw
    // beans survive into the glass form (keeping the cat's original look).
    val sol = (1f - glass) * alpha
    val gl = glass * alpha
    val faceA = max(1f - glass, glass * 0.5f) * alpha
    val bellyA = max(1f - glass, glass * 0.4f) * alpha
    val beanA = max(1f - glass, glass * 0.5f) * alpha

    val body = Color(0xFFFFFDFC)   // near-white fur
    val line = Color(0xFF7A6560)   // soft "drawn" outline
    val earIn = Color(0xFFF5C2D0)  // pink inner ear
    val eye = Color(0xFF4A3A34)    // soft near-black
    val cheek = Color(0xFFF6B4C4)  // blush
    val nose = Color(0xFF966E6C)   // nose + mouth
    val belly = Color(0xFFF7E6A6)  // pale butter-yellow belly patch
    val bean = Color(0xFFF3A9BC)   // paw toe beans
    val whisk = Color(0xFF968078)  // whiskers

    // ---- Tail: a cubic curl on the lower right, behind the body ----
    val tail = Path().apply {
        moveTo(62f, 58f)
        cubicTo(104f, 66f, 112f, 30f, 86f, 20f)
    }
    if (sol > 0.002f) {
        drawPath(tail, line.copy(alpha = sol), style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (gl > 0.002f) {
        drawPath(tail, Color.White.copy(alpha = gl * 0.7f), style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(tail, tint.copy(alpha = gl * 0.15f), style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // ---- Ears: two pointed triangles, drawn before the body so the body covers their base ----
    for (side in intArrayOf(-1, 1)) {
        val s = side.toFloat()
        val ear = Path().apply {
            moveTo(s * 78f, -46f)
            lineTo(s * 30f, -66f)
            lineTo(s * 60f, -122f)
            close()
        }
        if (sol > 0.002f) {
            drawPath(ear, body.copy(alpha = sol))
            drawPath(ear, line.copy(alpha = sol), style = Stroke(width = 4f, join = StrokeJoin.Round))
        }
        val innerEar = Path().apply {
            moveTo(s * 68f, -50f)
            lineTo(s * 38f, -66f)
            lineTo(s * 60f, -104f)
            close()
        }
        if (sol > 0.002f) drawPath(innerEar, earIn.copy(alpha = sol))
        if (gl > 0.002f) {
            // Glass ear: bright rim only, so it reads as glass without a heavy fill.
            drawPath(ear, Color.White.copy(alpha = gl * 0.75f), style = Stroke(width = 3f, join = StrokeJoin.Round))
        }
    }

    // ---- Body: a rounded square (head and body as one marshmallow shape) ----
    val bodyTL = Offset(-84f, -70f)
    val bodySize = Size(168f, 156f)
    val bodyR = CornerRadius(60f, 60f)
    if (sol > 0.002f) {
        drawRoundRect(body.copy(alpha = sol), bodyTL, bodySize, bodyR)
        drawRoundRect(line.copy(alpha = sol), bodyTL, bodySize, bodyR, style = Stroke(width = 4f))
    }
    if (gl > 0.002f) {
        drawRoundRect(Color.White.copy(alpha = gl * 0.16f), bodyTL, bodySize, bodyR)
        drawRoundRect(tint.copy(alpha = gl * 0.18f), bodyTL, bodySize, bodyR)
        drawRoundRect(Color.White.copy(alpha = gl * 0.85f), bodyTL, bodySize, bodyR, style = Stroke(width = 3f))
        // A soft top sheen so the glass body catches the light.
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = gl * 0.28f),
                0.6f to Color.Transparent
            ),
            topLeft = bodyTL,
            size = bodySize,
            cornerRadius = bodyR
        )
    }

    // ---- Soft body blush on the lower sides (echoes the reference art) ----
    if (sol > 0.002f) {
        for (bx in intArrayOf(-52, 52)) {
            drawOval(cheek.copy(alpha = sol * 0.18f), Offset(bx - 24f, 34f), Size(48f, 36f))
        }
    }

    // ---- Belly patch: a pale-yellow oval ----
    if (bellyA > 0.002f) {
        drawOval(belly.copy(alpha = bellyA), Offset(-38f, 30f), Size(76f, 44f))
    }

    // ---- Feet: two little smile-arcs at the bottom (the "ᵕᵕ") ----
    for (fx in intArrayOf(-24, 24)) {
        if (sol > 0.002f) {
            drawArc(
                color = line.copy(alpha = sol * 0.85f),
                startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(fx - 16f, 60f), size = Size(32f, 28f),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
        }
    }

    // ---- Cheeks: two blush ovals ----
    if (sol > 0.002f) {
        for (cx in intArrayOf(-54, 54)) {
            drawOval(cheek.copy(alpha = sol), Offset(cx - 17f, 10f), Size(34f, 18f))
        }
    }

    // ---- Whiskers: three per side, gently fanned ----
    if (sol > 0.002f) {
        val whiskerA = whisk.copy(alpha = sol * 0.9f)
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            drawLine(whiskerA, Offset(s * 58f, 4f), Offset(s * 96f, -2f), 2f, StrokeCap.Round)
            drawLine(whiskerA, Offset(s * 58f, 14f), Offset(s * 100f, 14f), 2f, StrokeCap.Round)
            drawLine(whiskerA, Offset(s * 58f, 24f), Offset(s * 96f, 30f), 2f, StrokeCap.Round)
        }
    }

    // ---- Eyes: tall ovals that squash shut on a blink into two happy arcs ----
    for (ex in intArrayOf(-32, 32)) {
        val ry = 17f * (1f - 0.86f * blink)
        if (blink < 0.82f && faceA > 0.002f) {
            drawOval(eye.copy(alpha = faceA), Offset(ex - 11f, -6f - ry), Size(22f, 2f * ry))
        }
        // The happy closed arc fades in as the eye shuts.
        val arcA = ((blink - 0.35f) / 0.65f).coerceIn(0f, 1f) * faceA
        if (arcA > 0.02f) {
            drawArc(
                color = eye.copy(alpha = arcA),
                startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(ex - 12f, -15f), size = Size(24f, 18f),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
        }
    }

    // ---- Nose + omega mouth ----
    if (faceA > 0.002f) {
        val noseCol = nose.copy(alpha = faceA)
        val noseTri = Path().apply {
            moveTo(-4f, 0f); lineTo(4f, 0f); lineTo(0f, 5f); close()
        }
        drawPath(noseTri, noseCol)
        drawArc(noseCol, 20f, 140f, false, Offset(-9f, 5f), Size(10f, 9f), style = Stroke(width = 3f, cap = StrokeCap.Round))
        drawArc(noseCol, 20f, 140f, false, Offset(-1f, 5f), Size(10f, 9f), style = Stroke(width = 3f, cap = StrokeCap.Round))
    }

    // ---- Paws: two round mittens on top, each swinging about where it meets the body ----
    for (side in intArrayOf(-1, 1)) {
        val s = side.toFloat()
        val pivot = Offset(s * 74f, 26f)         // the connection point with the body
        withTransform({ rotate(degrees = waveAngle * s, pivot = pivot) }) {
            val center = Offset(s * 98f, 30f)
            val r = 23f
            if (sol > 0.002f) {
                drawCircle(body.copy(alpha = sol), r, center)
                drawCircle(line.copy(alpha = sol), r, center, style = Stroke(width = 4f))
            }
            if (gl > 0.002f) {
                drawCircle(Color.White.copy(alpha = gl * 0.16f), r, center)
                drawCircle(Color.White.copy(alpha = gl * 0.85f), r, center, style = Stroke(width = 3f))
            }
            // Two toe beans near the top of the paw.
            if (beanA > 0.002f) {
                for (bx in floatArrayOf(-8f, 8f)) {
                    drawCircle(bean.copy(alpha = beanA), 3.5f, Offset(center.x + bx, center.y - 9f))
                }
            }
        }
    }
}

// ---- Timings, in milliseconds ----
// One place to retune the whole sequence; every phase above is expressed against these rather than
// against hard-coded numbers scattered through the drawing code.
// SPEED: overall launch-animation speed. A factor below 1 makes the whole sequence quicker; every
// duration below is multiplied by it, so the relative timing of the phases is preserved exactly.
private const val SPEED = 0.85f
private const val TOTAL_MS = 7700f * SPEED
private const val ENTER_MS = 700f * SPEED
private const val WAVE_END_MS = 2500f * SPEED      // paws finish waving here (before the blink)
private const val BLINK_START_MS = 2600f * SPEED   // eyes begin to close
private const val BLINK_END_MS = 3200f * SPEED     // eyes fully open again
private const val MORPH_START_MS = 3300f * SPEED
private const val MORPH_END_MS = 6600f * SPEED
private const val EXIT_START_MS = 6600f * SPEED
private const val WAVE_CYCLES = 3f // a whole number of wave swings, so the paws end at rest — not a duration
