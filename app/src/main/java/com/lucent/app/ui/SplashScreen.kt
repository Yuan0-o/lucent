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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
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
 * The launch animation: a little cat waves hello, gives a playful blink, then turns into liquid glass.
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
 * ### The cat
 *
 * Redrawn from a reference picture: a marshmallow-white kitten with a huge soft head, two little
 * round ear bumps, dot eyes, airbrushed pink cheeks, a wavy pink mouth, pink whiskers, a
 * butter-yellow tummy, a curled tail — and a plump blue fish napping on its head, which bobs
 * gently while the cat is solid. Its paws are NOT floating circles: they are slim vertical mitts —
 * rounded "stick" cross-sections, taller than they are wide — held in front of a wide torso and
 * overlapping it, so cat-plus-paws reads as one connected plush shape. The key proportions (body
 * 0.87 of head width, cheeks the widest point) are measured off the picture; the mitts are
 * deliberately slimmer than the reference's round paws (~0.24 of the body width) to open up the
 * chest and let the tummy patch show.
 *
 * ### The animation
 *
 * About seven and a half seconds, in five movements:
 *
 *  1. **Arrive** (0–0.7s) — the cat pops in with a slight overshoot, the way something alive enters.
 *  2. **Wave** (0.7–2.6s) — both paws swing about their shoulder joints, a cheeky little "hello".
 *     Because each paw is fused to the body, the swing is a lift of the whole arm about a pivot
 *     buried deep inside the torso: at any angle the paw still overlaps the body, so the silhouette
 *     never tears open. The two paws mirror each other, so the greeting reads as a happy,
 *     symmetric two-pawed pat-pat.
 *  3. **Blink** (2.6–3.3s) — a single playful blink: the dot eyes squeeze shut into happy little
 *     arcs and spring back open, with a tiny squash for character. This lands after the wave and
 *     before the glass, so the cat is unmistakably *itself* right before it transforms.
 *  4. **Become glass** (3.3–6.6s) — the solid cat cross-fades into the app's own material: a
 *     translucent pane tinted by the live palette, a bright rim, and a specular highlight that sweeps
 *     across it as it changes. It also *wobbles* — squashing and stretching, strongest at the
 *     midpoint and settling to nothing — which is what makes it read as having briefly turned to
 *     liquid rather than simply having faded. This is the part worth lingering on, so it gets the
 *     largest share of the running time.
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
        // Entrance: 0.62 -> 1.0 scale with a small overshoot, plus a fade in.
        val enter = (t / ENTER_MS).coerceIn(0f, 1f)
        val enterEased = 1f - (1f - enter) * (1f - enter)                   // ease-out
        val overshoot = sin(enter * PI.toFloat()) * 0.06f                    // brief bulge past 1.0
        val scaleIn = 0.62f + 0.38f * enterEased + overshoot

        // Wave: both arms lift and swing about their shoulder pivots, tapering off as the blink
        // approaches. The amplitude is gentle — the paws are long, body-attached mitts, and a
        // small swing of a long arm already reads as an enthusiastic hello.
        val waveT = ((t - ENTER_MS) / (WAVE_END_MS - ENTER_MS)).coerceIn(0f, 1f)
        val waveDeg = sin(waveT * WAVE_CYCLES * 2f * PI.toFloat()) * WAVE_AMP_DEG * (1f - waveT * 0.3f)

        // Blink: one playful close-and-open, sitting between the wave and the morph. eyeOpen runs
        // 1 -> 0 -> 1; blinkSquash gives the whole cat a tiny bounce while the eyes are shut.
        val blinkT = ((t - BLINK_START_MS) / (BLINK_END_MS - BLINK_START_MS)).coerceIn(0f, 1f)
        val blinking = t in BLINK_START_MS..BLINK_END_MS
        val eyeOpen = if (t < BLINK_START_MS) 1f else 1f - sin(blinkT * PI.toFloat())
        val blinkSquash = if (blinking) sin(blinkT * PI.toFloat()) * 0.03f else 0f

        // Morph: 0 = solid cat, 1 = glass cat.
        val glass = ((t - MORPH_START_MS) / (MORPH_END_MS - MORPH_START_MS)).coerceIn(0f, 1f)
        val glassEased = glass * glass * (3f - 2f * glass)
        // Liquid wobble: strongest halfway through the change, nothing at either end.
        val wobble = sin(glass * PI.toFloat()) * 0.055f * sin(t / 90f)

        // The fish naps on the cat's head and bobs very slightly while the cat is solid — a tiny
        // idle sign of life. The bob settles to zero as the glass takes over, so the silhouette
        // holds perfectly still through the morph.
        val fishBob = sin(t / FISH_BOB_MS) * FISH_BOB_AMP * (1f - glassEased)

        // Exit: float up and dissolve.
        val exit = ((t - EXIT_START_MS) / (TOTAL_MS - EXIT_START_MS)).coerceIn(0f, 1f)
        val exitEased = exit * exit
        val alpha = (1f - exitEased).coerceIn(0f, 1f) * enterEased.coerceAtLeast(0.001f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // The cat lives in a ~780-unit design space (it grew a fish and much bigger paws, so
            // the space is larger than the old 470 to keep the same on-screen presence).
            val unit = size.minDimension / 780f
            val cx = size.width / 2f
            val cy = size.height / 2f - 36f * unit - exitEased * 90f * unit

            withTransform({
                translate(left = cx, top = cy)
                scale(
                    scaleX = unit * scaleIn * (1f + wobble),
                    scaleY = unit * scaleIn * (1f - wobble - blinkSquash),
                    pivot = Offset.Zero
                )
                // The mitts swing about their own planted bottom caps inside drawCat — a
                // symmetric, two-pawed "hello" — rather than the whole drawing rocking.
            }) {
                drawCat(
                    glass = glassEased,
                    alpha = alpha,
                    waveDeg = waveDeg,
                    eyeOpen = eyeOpen,
                    fishBob = fishBob,
                    tint = paletteColors.firstOrNull() ?: Color.White
                )
            }
        }

        // The wordmark arrives with the glass, so the two land together. It sits well below the
        // figure: the cat's body reaches ~210 design units under its own centre (plus the soft
        // blush at its base), and the old offset let the lower body sit right on the text — so the
        // name now clears the whole silhouette with a little air to spare.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 300.dp),
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

// ---- Palette, sampled straight from the reference picture ----
private val Fur = Color(0xFFFFFDFA)        // marshmallow white, barely warm
private val Line = Color(0xFF8C7276)       // warm grey-brown crayon outline
private val EyeColor = Color(0xFF4E3B40)   // dot eyes
private val BlushPink = Color(0xFFF6B8CE)  // airbrushed pink wash (cheeks, ears, paw tips)
private val WhiskerPink = Color(0xFFF0A6BE)
private val MouthPink = Color(0xFFEE8FB0)  // the wavy little mouth
private val BellyCream = Color(0xFFF7EFC8) // butter-yellow tummy patch
private val FishFill = Color(0xFFDFF0FC)   // the napping fish
private val FishLine = Color(0xFF8FB4D8)
private val FishEye = Color(0xFF4A7BAA)

/**
 * Draws the Lucent cat at the origin, in a space roughly 780 units tall.
 *
 * Faithful to the reference picture: a marshmallow-white kitten whose huge soft head (a rounded
 * square unioned with two little round ear bumps, so head-plus-ears is ONE continuous outline)
 * carries a napping blue fish; dot eyes, airbrushed cheeks, a wavy pink mouth, pink whiskers, a
 * butter tummy and a curled tail below. The paws are slim vertical mitts — outlined rounded
 * rectangles with fully rounded end caps, like little stick cross-sections — overlapping the torso
 * and connected to the body; they wave by rotating about shoulder pivots buried deep inside the
 * body, each mitt tilting with its arm, so however far they swing the join never opens. All of it
 * is plain Canvas primitives
 * (round rects, ovals, arcs, lines and a few bezier paths united with [PathOperation.Union]): no
 * image asset, scales to any screen, and can be morphed arbitrarily, which is the entire trick in
 * the "become glass" phase.
 *
 * [glass] cross-fades between the drawn cat (0) and the glass one (1). The silhouette that turns to
 * glass is the head (with ears), body, both paws, the fish and the tail; the painted details (eyes,
 * cheeks, whiskers, mouth, tummy, blush washes, the fish's face) fade out as the glass takes over,
 * so the glass reads as the cat's *form* rather than a cat wearing a painted face. [waveDeg] swings
 * each paw about its planted bottom cap, [eyeOpen] drives the blink (1 = wide open, 0 = shut), [fishBob]
 * nudges the fish up and down on the crown, and [tint] is the live palette colour the glass picks up.
 */
private fun DrawScope.drawCat(
    glass: Float,
    alpha: Float,
    waveDeg: Float,
    eyeOpen: Float,
    fishBob: Float,
    tint: Color
) {
    if (alpha <= 0.001f) return

    val solid = (1f - glass) * alpha    // fur, outline and every painted detail fade as glass arrives
    val glassy = glass * alpha          // the translucent glass rendition fades in over the top

    // ---- Silhouette geometry ----

    // Head: one continuous outline = big rounded square ∪ two round ear bumps ∪ two cheek
    // bulges. The union keeps the crayon line unbroken everywhere, and the cheeks make the head
    // widest at its lower half — measured straight off the reference, where the face melts into
    // the shoulders with barely a pinch.
    val head = Path().apply {
        addRoundRect(RoundRect(Rect(-130f, -152f, 130f, 28f), CornerRadius(82f, 82f)))
    }
        .union(circlePath(-90f, -138f, 36f)).union(circlePath(90f, -138f, 36f))
        .union(circlePath(-98f, -4f, 45f)).union(circlePath(98f, -4f, 45f))

    // Body: a wide rounded slab — almost as wide as the head (0.87 of it, like the reference) —
    // whose top rises behind the cheeks so there is no neck pinch between face and shoulders.
    val body = Path().apply {
        addRoundRect(RoundRect(Rect(-120f, 0f, 120f, 210f), CornerRadius(68f, 68f)))
    }

    // Paws: slim vertical mitts held IN FRONT of the torso — rounded-rectangle "stick" cross
    // sections, clearly taller than they are wide, with the corner radius equal to the half-width
    // so both ends are fully rounded caps. Narrower than the old round paws, they reveal more of
    // the chest (and the whole tummy patch) while still overlapping the torso, so cat-plus-paws
    // keeps reading as one connected plush shape. The wave is a metronome swing: each mitt's
    // BOTTOM cap stays planted on the torso while the whole stick rotates left and right about
    // that fixed bottom point, so the free top cap sweeps side to side like a waving hand and
    // the join at the base can never open. Left paw gets +waveDeg and right paw -waveDeg — a
    // mirrored, symmetric two-pawed hello.
    val pawHalfWidth = 29f
    val pawHalfHeight = 57f
    val pawSize = Size(pawHalfWidth * 2f, pawHalfHeight * 2f)
    val pawCorner = CornerRadius(pawHalfWidth, pawHalfWidth)
    // Rest centres are fixed — all motion comes from rotating about each mitt's bottom cap. The
    // rest y sits a touch lower than the old round paw so the taller mitt's top cap still clears
    // the cheeks and stays visible.
    val pawL = Offset(-78f, 96f)
    val pawR = Offset(78f, 96f)

    // The fish napping on the crown: a plump, nearly round body ∪ two round tail petals, nose to
    // the right. It sits proudly ABOVE the ears — in the reference the fish owns the very top of
    // the silhouette — while its belly still rests on the head. fy carries the idle bob.
    val fy = -180f + fishBob
    val fish = Path().apply {
        addOval(Rect(-40f, fy - 38f, 52f, fy + 38f))
    }.union(fishPetal(fy, -1f)).union(fishPetal(fy, 1f))

    // The curled tail poking out bottom-left, drawn as strokes (outline pass + fill pass) so it
    // reads as a little tube.
    val tail = Path().apply {
        moveTo(-110f, 180f)
        cubicTo(-128f, 192f, -142f, 192f, -149f, 182f)
        cubicTo(-155f, 173f, -153f, 160f, -146f, 154f)
    }

    // ---- SOLID pass: the drawn cat ----
    if (solid > 0.002f) {
        // Tail first, tucked behind the body.
        drawPath(tail, Line.copy(alpha = solid), style = Stroke(width = 19f, cap = StrokeCap.Round))
        drawPath(tail, Fur.copy(alpha = solid), style = Stroke(width = 11f, cap = StrokeCap.Round))

        // Body, with a soft pink wash along its bottom edge.
        drawPath(body, Fur.copy(alpha = solid))
        drawPath(body, Line.copy(alpha = solid), style = Stroke(width = 7f))
        blush(Offset(0f, 200f), 70f, 0.43f * solid)

        // Butter tummy patch.
        val bellyRect = Rect(-44f, 86f, 44f, 146f)
        val bellyPath = Path().apply { addRoundRect(RoundRect(bellyRect, CornerRadius(26f, 26f))) }
        drawPath(bellyPath, BellyCream.copy(alpha = solid))
        drawPath(bellyPath, Line.copy(alpha = solid), style = Stroke(width = 4f))

        // Paws over the body: slim outlined vertical mitts, each with a pink wash near its lower
        // rounded end. Being drawn after the body, the paw hides the stretch of body outline it
        // overlaps — which is precisely how the reference reads. Each mitt rotates about its own
        // bottom cap — the fixed contact point on the torso — so it swings like a little
        // metronome while its base never leaves the body.
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            val c = if (i == 0) pawL else pawR
            val bottomPivot = Offset(c.x, c.y + pawHalfHeight)
            withTransform({ rotate(degrees = waveDeg * -side, pivot = bottomPivot) }) {
                val topLeft = Offset(c.x - pawHalfWidth, c.y - pawHalfHeight)
                drawRoundRect(Fur.copy(alpha = solid), topLeft, pawSize, pawCorner)
                drawRoundRect(Line.copy(alpha = solid), topLeft, pawSize, pawCorner, style = Stroke(width = 7f))
                blush(Offset(c.x + side * 3f, c.y + 28f), 30f, 0.53f * solid)
            }
        }

        // Head over the paws' tops, then its airbrushed pinks: ear tips, big cheeks, side glow.
        drawPath(head, Fur.copy(alpha = solid))
        drawPath(head, Line.copy(alpha = solid), style = Stroke(width = 7f))
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            blush(Offset(side * 90f, -140f), 34f, 0.49f * solid)
            blush(Offset(side * 100f, -6f), 54f, 0.65f * solid)
            blush(Offset(side * 126f, -64f), 38f, 0.33f * solid)
        }

        // The fish, napping. Face details: a dot eye near the nose, a little gill arc, a ring spot.
        drawPath(fish, FishFill.copy(alpha = solid))
        drawPath(fish, FishLine.copy(alpha = solid), style = Stroke(width = 5f))
        drawCircle(FishEye.copy(alpha = solid), 5f, Offset(32f, fy - 7f))
        drawArc(
            FishLine.copy(alpha = solid), -55f, 110f, false,
            Offset(4f, fy - 13f), Size(20f, 26f), style = Stroke(width = 3.2f, cap = StrokeCap.Round)
        )
        drawCircle(FishLine.copy(alpha = solid), 5.5f, Offset(-4f, fy + 7f), style = Stroke(width = 2.6f))

        // Whiskers: three pink strokes a side, fanning gently.
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            drawLine(WhiskerPink.copy(alpha = solid), Offset(side * 102f, -34f), Offset(side * 138f, -42f), 5.5f, StrokeCap.Round)
            drawLine(WhiskerPink.copy(alpha = solid), Offset(side * 102f, -20f), Offset(side * 138f, -20f), 5.5f, StrokeCap.Round)
            drawLine(WhiskerPink.copy(alpha = solid), Offset(side * 102f, -6f), Offset(side * 138f, 0f), 5.5f, StrokeCap.Round)
        }

        // Eyes: solid dots, squashing into happy arcs during the blink.
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            val ex = side * 48f
            if (eyeOpen > 0.22f) {
                val eh = 9.5f * eyeOpen
                drawOval(EyeColor.copy(alpha = solid), Offset(ex - 9.5f, -34f - eh), Size(19f, eh * 2f))
            } else {
                // Shut: a happy upward arch.
                drawArc(
                    EyeColor.copy(alpha = solid), 205f, 130f, false,
                    Offset(ex - 10f, -39f), Size(20f, 14f), style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
            }
        }

        // The wavy mouth: two little downward arcs meeting in an upward point — a soft "w".
        drawArc(
            MouthPink.copy(alpha = solid), 10f, 160f, false,
            Offset(-18f, -26f), Size(20f, 16f), style = Stroke(width = 6.5f, cap = StrokeCap.Round)
        )
        drawArc(
            MouthPink.copy(alpha = solid), 10f, 160f, false,
            Offset(-2f, -26f), Size(20f, 16f), style = Stroke(width = 6.5f, cap = StrokeCap.Round)
        )
    }

    // ---- GLASS pass: the silhouette turned to liquid glass ----
    // Same recipe as the app's own material: a faint white pane + a wash of the live palette tint,
    // a bright rim, and a soft top sheen on the two big panes. The fish and tail come along too —
    // they are part of the form, and the form is what turns to glass.
    if (glassy > 0.002f) {
        drawPath(tail, Color.White.copy(alpha = glassy * 0.55f), style = Stroke(width = 9f, cap = StrokeCap.Round))

        val panes = listOf(body, head, fish)
        for (pane in panes) {
            drawPath(pane, Color.White.copy(alpha = glassy * 0.16f))
            drawPath(pane, tint.copy(alpha = glassy * 0.20f))
            drawPath(pane, Color.White.copy(alpha = glassy * 0.75f), style = Stroke(width = 3f))
        }
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            val c = if (i == 0) pawL else pawR
            // Same bottom-cap swing as the solid mitts. By the time the glass shows, the wave
            // has settled to zero, but keeping the transform guarantees the two renditions can
            // never tear apart if the timing is ever retuned.
            val bottomPivot = Offset(c.x, c.y + pawHalfHeight)
            withTransform({ rotate(degrees = waveDeg * -side, pivot = bottomPivot) }) {
                val topLeft = Offset(c.x - pawHalfWidth, c.y - pawHalfHeight)
                drawRoundRect(Color.White.copy(alpha = glassy * 0.16f), topLeft, pawSize, pawCorner)
                drawRoundRect(tint.copy(alpha = glassy * 0.20f), topLeft, pawSize, pawCorner)
                drawRoundRect(Color.White.copy(alpha = glassy * 0.75f), topLeft, pawSize, pawCorner, style = Stroke(width = 3f))
            }
        }

        // Top sheen so the big panes catch the light.
        drawPath(
            path = head,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = glassy * 0.28f),
                1f to Color.Transparent,
                startY = -152f,
                endY = -20f
            )
        )
        drawPath(
            path = body,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = glassy * 0.28f),
                1f to Color.Transparent,
                startY = 0f,
                endY = 110f
            )
        )
    }
}

/** A soft airbrushed pink wash — the reference's blush is sprayed, not painted, so it has no edge. */
private fun DrawScope.blush(center: Offset, radius: Float, alpha: Float) {
    if (alpha <= 0.004f) return
    drawCircle(
        brush = Brush.radialGradient(
            0f to BlushPink.copy(alpha = alpha),
            0.55f to BlushPink.copy(alpha = alpha * 0.5f),
            1f to Color.Transparent,
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** One round petal of the fish's forked tail; [sg] = -1 for the upper petal, +1 for the lower. */
private fun fishPetal(fy: Float, sg: Float): Path = Path().apply {
    moveTo(-32f, fy + sg * 10f)
    cubicTo(-46f, fy + sg * 20f, -58f, fy + sg * 28f, -68f, fy + sg * 24f)
    cubicTo(-74f, fy + sg * 20f, -74f, fy + sg * 14f, -66f, fy + sg * 7f)
    cubicTo(-59f, fy + sg * 2f, -50f, fy, -32f, fy)
    close()
}

private fun circlePath(cx: Float, cy: Float, r: Float): Path =
    Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }

/** Silhouettes are built by uniting simple shapes, which is what keeps their outlines continuous. */
private fun Path.union(other: Path): Path = Path.combine(PathOperation.Union, this, other)

// ---- Timings, in milliseconds ----
// One place to retune the whole sequence; every phase above is expressed against these rather than
// against hard-coded numbers scattered through the drawing code.
// SPEED: overall launch-animation speed. A factor below 1 makes the whole sequence quicker; every
// duration below is multiplied by it, so the relative timing of the phases is preserved exactly.
private const val SPEED = 0.85f
private const val TOTAL_MS = 7700f * SPEED
private const val ENTER_MS = 700f * SPEED
private const val WAVE_END_MS = 2600f * SPEED     // paws wave from ENTER_MS up to here
private const val BLINK_START_MS = 2600f * SPEED  // the playful blink sits between wave and morph
private const val BLINK_END_MS = 3300f * SPEED
private const val MORPH_START_MS = 3300f * SPEED
private const val MORPH_END_MS = 6600f * SPEED
private const val EXIT_START_MS = 6600f * SPEED
private const val WAVE_CYCLES = 3.5f              // a count of paw swings, not a duration — left unscaled
private const val WAVE_AMP_DEG = 14f              // metronome swing about each mitt's planted
                                                  // bottom cap; ±14° sweeps the free top cap
                                                  // ~28 design units to either side
private const val FISH_BOB_MS = 260f              // period divisor of the fish's tiny idle bob
private const val FISH_BOB_AMP = 2.2f             // …and its amplitude, in design units
