package com.lucent.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
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
 * The launch animation: a cat waves hello, then turns into liquid glass.
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
 * Five seconds, in four movements:
 *
 *  1. **Arrive** (0–0.5s) — the cat pops in with a slight overshoot, the way something alive enters.
 *  2. **Wave** (0.5–2.6s) — a paw swings back and forth, the head tips with it, and there's one
 *     blink. The tilt matters more than it sounds: a paw waving on a perfectly still head reads as a
 *     machine part moving, whereas the whole body committing to the gesture reads as a greeting.
 *  3. **Become glass** (2.6–4.3s) — the solid cat cross-fades into the app's own material: a
 *     translucent pane tinted by the live palette, a bright rim, and a specular highlight that sweeps
 *     across the silhouette as it changes. It also *wobbles* — squashing and stretching, strongest at
 *     the midpoint and settling to nothing — which is what makes it read as having briefly turned to
 *     liquid rather than simply having faded.
 *  4. **Leave** (4.3–5.0s) — it floats up and dissolves, and the app is already there behind it.
 *
 * The cat is drawn with plain Canvas primitives — no image asset, no vector drawable — so it is a
 * few hundred bytes of code, scales to any screen without a folder of densities, and can be morphed
 * arbitrarily, which is the entire trick in movement 3.
 *
 * ### Getting out of it
 *
 * Five seconds is generous the first time and long the hundredth, so **a tap, or a back press,
 * finishes it immediately** — and the app behind is already composed, so skipping is instant rather
 * than a shortcut into more waiting. Completion is also driven by a plain `delay`, not by the frame
 * clock that drives the drawing: if that clock were ever starved the cat would freeze, and a frozen
 * splash that never ends is an app that never starts. The animation may stall; the launch may not.
 */
@Composable
fun LucentSplash(
    paletteColors: List<Color>,
    backdropColor: Color,
    onFinished: () -> Unit
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
            // Also swallows every touch, so nothing lands on the app composing underneath.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { finish() })
            }
    ) {
        // The same living background the app itself uses, so the splash is the app arriving rather
        // than a separate screen shown in front of it.
        FluidGlassBackground(
            palette = paletteColors,
            backdropColor = backdropColor,
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

        // Blink: one, mid-wave. A short dip of the eyelid, not a long one — a slow blink reads sleepy.
        val blinkPhase = ((t - BLINK_AT_MS) / BLINK_MS).coerceIn(0f, 1f)
        val blink = if (t < BLINK_AT_MS || blinkPhase >= 1f) 0f else sin(blinkPhase * PI.toFloat())

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
                rotate(degrees = waveAngle * 0.18f, pivot = Offset.Zero)
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
            Text(
                "Tap to skip",
                color = onGradient.copy(alpha = glassEased * (1f - exitEased) * 0.35f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/**
 * Draws the cat at the origin, in a space roughly 190 units wide.
 *
 * ### The shape, and why it changed
 *
 * The first cat was a circle with an oval stuck under it, two big shiny eyes and a triangle nose —
 * i.e. a diagram of a cat rather than a drawing of one, and it read as a snowman with ears. What
 * makes a hand-drawn chibi cat cute is almost entirely proportion and silhouette:
 *
 *  - **One fused blob, not two shapes.** Head and body are a single soft outline that bulges wider
 *    at the bottom. The moment there is a visible neck-line between two ovals the charm is gone.
 *  - **Ears grown out of that same outline**, as soft peaks rather than triangles pasted on top.
 *  - **Features small, low and wide-set.** Big eyes read as an anime character; small eyes placed
 *    low on a large head read as a baby animal, which is the effect wanted here.
 *  - **A drawn line.** Both of the references are line art, and the soft dark outline is doing a lot
 *    of the work — a flat silhouette with no line looks like a sticker.
 *  - **An ω mouth, blush, and a couple of whisker ticks.** Three tiny marks that carry the whole
 *    expression.
 *
 * Everything is built from `cubicTo` rather than quadratic curves: the cubic API has been stable
 * since Compose 1.0, whereas `quadraticBezierTo` has since been renamed, and a splash screen is a
 * silly thing to break a build over.
 *
 * [glass] cross-fades between the drawn cat (0) and the glass one (1) — the same silhouette both
 * times, so the shape never jumps, only the material. [waveAngle] swings the raised paw, [blink]
 * closes the eyes, and [tint] is the live palette colour the glass picks up.
 */
private fun DrawScope.drawCat(
    glass: Float,
    alpha: Float,
    waveAngle: Float,
    blink: Float,
    tint: Color
) {
    if (alpha <= 0.001f) return

    val solid = (1f - glass) * alpha
    val glassy = glass * alpha

    // ---- One fused silhouette: body, head and both ears in a single closed path ----
    val silhouette = Path().apply {
        // Start at the left cheek and run up to the left ear.
        moveTo(-78f, -6f)
        cubicTo(-80f, -34f, -74f, -52f, -62f, -60f)
        // Left ear: up to the peak, back down into the dip between the ears.
        lineTo(-58f, -104f)
        cubicTo(-40f, -92f, -28f, -80f, -20f, -68f)
        // The dip between the ears — shallow, so the head still reads as one round mass.
        cubicTo(-8f, -72f, 8f, -72f, 20f, -68f)
        // Right ear.
        cubicTo(28f, -80f, 40f, -92f, 58f, -104f)
        lineTo(62f, -60f)
        cubicTo(74f, -52f, 80f, -34f, 78f, -6f)
        // Right side down into the wider body.
        cubicTo(76f, 34f, 74f, 62f, 56f, 80f)
        // The bottom, deliberately broad and flat-ish: a sitting cat, not a ball.
        cubicTo(34f, 96f, -34f, 96f, -56f, 80f)
        // Back up the left side to the start.
        cubicTo(-74f, 62f, -76f, 34f, -78f, -6f)
        close()
    }

    val furColor = Color(0xFFFFFAF4)
    val lineColor = Color(0xFF7A6560)
    val inkColor = Color(0xFF5C4A48)
    val blushColor = Color(0xFFFFA8BE)

    // ---- The drawn cat ----
    if (solid > 0.002f) {
        drawPath(silhouette, furColor.copy(alpha = solid))

        // Inner ears — small soft triangles set inside each peak.
        drawPath(
            Path().apply { moveTo(-56f, -92f); lineTo(-32f, -70f); lineTo(-56f, -62f); close() },
            blushColor.copy(alpha = solid * 0.55f)
        )
        drawPath(
            Path().apply { moveTo(56f, -92f); lineTo(32f, -70f); lineTo(56f, -62f); close() },
            blushColor.copy(alpha = solid * 0.55f)
        )

        // A soft belly patch, the way the reference has a pale oval on the front.
        drawOval(
            color = Color(0xFFFFEFC9).copy(alpha = solid * 0.55f),
            topLeft = Offset(-34f, 36f),
            size = Size(68f, 46f)
        )

        // The outline. Drawn after the fills so it stays crisp on top of them.
        drawPath(silhouette, color = lineColor.copy(alpha = solid), style = Stroke(width = 3.4f))

        // ---- Face: small, low, wide-set ----
        val eyeOpen = (1f - blink).coerceIn(0.05f, 1f)
        listOf(-30f, 30f).forEach { ex ->
            if (blink > 0.6f) {
                // Closed: a short downward arc reads far sweeter than a squashed oval.
                drawArc(
                    color = inkColor.copy(alpha = solid),
                    startAngle = 200f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(ex - 9f, -8f), size = Size(18f, 12f),
                    style = Stroke(width = 3.2f, cap = StrokeCap.Round)
                )
            } else {
                drawOval(
                    color = inkColor.copy(alpha = solid),
                    topLeft = Offset(ex - 8.5f, -4f - 11f * eyeOpen),
                    size = Size(17f, 22f * eyeOpen)
                )
            }
        }

        // The ω mouth: two small arcs meeting under the nose. This one mark does more for the
        // expression than every other feature combined.
        drawArc(
            color = inkColor.copy(alpha = solid),
            startAngle = 170f, sweepAngle = 200f, useCenter = false,
            topLeft = Offset(-13f, 8f), size = Size(13f, 11f),
            style = Stroke(width = 2.8f, cap = StrokeCap.Round)
        )
        drawArc(
            color = inkColor.copy(alpha = solid),
            startAngle = 170f, sweepAngle = 200f, useCenter = false,
            topLeft = Offset(0f, 8f), size = Size(13f, 11f),
            style = Stroke(width = 2.8f, cap = StrokeCap.Round)
        )

        // Blush, sitting just outside the eyes and slightly below them.
        drawOval(blushColor.copy(alpha = solid * 0.42f), Offset(-64f, 6f), Size(26f, 15f))
        drawOval(blushColor.copy(alpha = solid * 0.42f), Offset(38f, 6f), Size(26f, 15f))

        // Two whisker ticks a side — short, angled, and pink rather than grey, as in the reference.
        val w = blushColor.copy(alpha = solid * 0.75f)
        drawLine(w, Offset(-74f, 2f), Offset(-92f, -3f), 2.6f, StrokeCap.Round)
        drawLine(w, Offset(-74f, 12f), Offset(-92f, 13f), 2.6f, StrokeCap.Round)
        drawLine(w, Offset(74f, 2f), Offset(92f, -3f), 2.6f, StrokeCap.Round)
        drawLine(w, Offset(74f, 12f), Offset(92f, 13f), 2.6f, StrokeCap.Round)

        // A curled tail peeking out on the right.
        val tail = Path().apply {
            moveTo(70f, 66f)
            cubicTo(104f, 74f, 116f, 52f, 104f, 36f)
            cubicTo(98f, 28f, 88f, 30f, 88f, 40f)
        }
        drawPath(tail, color = lineColor.copy(alpha = solid), style = Stroke(width = 3.4f, cap = StrokeCap.Round))

        // Two little toe arcs along the bottom edge, like the reference's front paws.
        listOf(-20f, 20f).forEach { px ->
            drawArc(
                color = lineColor.copy(alpha = solid * 0.85f),
                startAngle = 195f, sweepAngle = 150f, useCenter = false,
                topLeft = Offset(px - 15f, 66f), size = Size(30f, 26f),
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )
        }
    }

    // ---- The glass cat ----
    if (glassy > 0.002f) {
        drawPath(silhouette, Color.White.copy(alpha = glassy * 0.16f))
        drawPath(silhouette, tint.copy(alpha = glassy * 0.20f))
        drawPath(silhouette, color = Color.White.copy(alpha = glassy * 0.75f), style = Stroke(width = 2.8f))
        clipPath(silhouette) {
            val travel = -190f + 380f * glass
            withTransform({ rotate(degrees = -28f, pivot = Offset(travel, 0f)) }) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = glassy * 0.38f),
                        1f to Color.Transparent
                    ),
                    topLeft = Offset(travel - 44f, -180f),
                    size = Size(88f, 360f)
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = glassy * 0.30f),
                    0.55f to Color.Transparent
                ),
                topLeft = Offset(-100f, -120f),
                size = Size(200f, 220f)
            )
        }
        // The face survives as faint engraving, so the glass is still recognisably the same cat.
        listOf(-30f, 30f).forEach { ex ->
            drawOval(
                color = Color.White.copy(alpha = glassy * 0.55f),
                topLeft = Offset(ex - 8f, -14f),
                size = Size(16f, 20f)
            )
        }
    }

    // ---- The waving paw ----
    // A small rounded nub on the cat's right, swung from the shoulder rather than spun about its own
    // centre, so it reads as an arm lifting rather than a part rotating.
    withTransform({ rotate(degrees = waveAngle, pivot = Offset(58f, 34f)) }) {
        val paw = Path().apply { addOval(Rect(62f, -18f, 100f, 26f)) }
        if (solid > 0.002f) {
            drawPath(paw, furColor.copy(alpha = solid))
            drawPath(paw, color = lineColor.copy(alpha = solid), style = Stroke(width = 3.2f))
            // Toe beans.
            drawCircle(blushColor.copy(alpha = solid * 0.6f), 4f, Offset(74f, -6f))
            drawCircle(blushColor.copy(alpha = solid * 0.6f), 4f, Offset(85f, -8f))
        }
        if (glassy > 0.002f) {
            drawPath(paw, Color.White.copy(alpha = glassy * 0.18f))
            drawPath(paw, tint.copy(alpha = glassy * 0.18f))
            drawPath(paw, color = Color.White.copy(alpha = glassy * 0.7f), style = Stroke(width = 2.6f))
        }
    }
}

// ---- Timings, in milliseconds ----
// One place to retune the whole sequence; every phase above is expressed against these rather than
// against hard-coded numbers scattered through the drawing code.
private const val TOTAL_MS = 5000f
private const val ENTER_MS = 500f
private const val MORPH_START_MS = 2600f
private const val MORPH_END_MS = 4300f
private const val EXIT_START_MS = 4300f
private const val BLINK_AT_MS = 1500f
private const val BLINK_MS = 260f
private const val WAVE_CYCLES = 3.5f
