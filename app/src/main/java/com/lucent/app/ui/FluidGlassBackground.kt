package com.lucent.app.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalInspectionMode
import com.lucent.app.nativebridge.LucentNative
import kotlin.math.cos
import kotlin.math.sin

/**
 * The drifting blob background.
 *
 * ### Why this was rewritten for performance
 *
 * The first version was correct but expensive, and its cost fell on the worst possible moment: the
 * first scroll after launch. Three things stacked up, and every one of them ran on *every frame*:
 *
 *  1. **Eighteen [androidx.compose.animation.core.InfiniteTransition] animations.** Each blob had
 *     three independent `animateFloat`s (X oscillator, Y oscillator, pulse), so eighteen animation
 *     state objects were being ticked and read per frame. Every one is a snapshot read that
 *     invalidates the composable that observes it, and the scheduling overhead of eighteen of them
 *     is real. They are now a *single* frame clock ([withInfiniteAnimationFrameNanos]) read once;
 *     all eighteen oscillators are derived from that one elapsed-time value with plain arithmetic.
 *
 *  2. **A full-screen `saveLayer` every frame.** The old code opened an offscreen layer the size of
 *     the display, drew six blobs into it, then composited it down — an allocation and a blit of a
 *     screen-sized buffer, sixty times a second, for the whole life of the app. The offscreen pass
 *     is only actually needed on *dark* backdrops (where blobs blend additively and must fuse with
 *     each other before meeting the backdrop). On light backdrops plain over-compositing is
 *     identical whether it goes through a layer or not, so the layer is dropped entirely there. When
 *     it *is* needed, it's expressed as a [CompositingStrategy.Offscreen] `graphicsLayer` on the
 *     Canvas, which lets Compose own the buffer and reuse it rather than us allocating one by hand
 *     each frame.
 *
 *  3. **Per-frame [Brush] allocation.** Six `Brush.radialGradient`s were allocated on every frame.
 *     The whole brush is now hoisted into a [remember] keyed on the palette — built once at a fixed
 *     *unit* radius around the origin — and the draw loop moves, sizes, and (for squares) spins it
 *     with a single cheap canvas transform ([withTransform]) instead of rebuilding it. In steady
 *     state the per-frame cost is now zero allocations, which is what keeps the frame rate smooth
 *     through state transitions (opening a detail page, a list fling) instead of stuttering when GC
 *     and another busy frame collide.
 *
 * The motion is otherwise unchanged — same six blobs, same Lissajous paths, same periods, same
 * breathing pulse, same additive glow on dark and plain wash on light. What *has* changed is the
 * shapes: they used to be three fixed circles and three fixed rounded squares, and they now morph
 * continuously between the two on very long, non-repeating cycles (see the morph constants below).
 * That addition costs nothing per frame — it is one number fed to the same single draw call — so the
 * background still costs a fraction of what the original did, which is what stops the first scroll
 * from stuttering while the background and Haze's blur of it fight for the same frame.
 */

private const val BLOB_COUNT = 6
private const val TAU = (2.0 * Math.PI).toFloat()

// Per-blob motion constants, pulled out of the composable so they're compiled once and never
// reallocated. These reproduce the previous layout and timing exactly.
private val BASE_X = floatArrayOf(0.30f, 0.68f, 0.50f, 0.22f, 0.78f, 0.44f)
private val BASE_Y = floatArrayOf(0.28f, 0.34f, 0.62f, 0.74f, 0.66f, 0.20f)
private val AMP_X = floatArrayOf(0.20f, 0.18f, 0.24f, 0.16f, 0.14f, 0.22f)
private val AMP_Y = floatArrayOf(0.16f, 0.22f, 0.14f, 0.20f, 0.18f, 0.24f)
private val PHASE_X = floatArrayOf(0.0f, 1.1f, 2.3f, 3.4f, 4.6f, 5.7f)
private val PHASE_Y = floatArrayOf(1.6f, 3.0f, 0.4f, 2.1f, 5.0f, 3.8f)
private val SIZE_FACTOR = floatArrayOf(1.05f, 0.85f, 1.20f, 0.75f, 0.95f, 1.10f)

// Oscillator periods, in milliseconds. The X and Y periods differ per blob, which is what turns a
// pair of sine waves into a slowly-precessing Lissajous curve. Pulse breathes on its own period.
// These are the exact values the old `tween(durationMillis = …)` calls used.
private val PERIOD_X = floatArrayOf(6900f, 8200f, 9500f, 10800f, 12100f, 13400f)
private val PERIOD_Y = floatArrayOf(8500f, 9500f, 10500f, 11500f, 12500f, 13500f)
private val PERIOD_PULSE = floatArrayOf(3100f, 3800f, 4500f, 5200f, 5900f, 6600f)

// ---- Shape morphing (added task 2) ----
//
// The blobs used to be *fixed*: three circles and three rounded squares, forever. Now every blob
// continuously morphs between the two and back again.
//
// The trick that makes this cheap is that a circle and a rounded square are the same primitive. A
// rounded rectangle whose corner radius equals half its side IS a circle, so the entire morph is one
// animated number handed to a single `drawRoundRect` — no path interpolation, no shape library, no
// extra draw call, and the two endpoints are geometrically exact rather than approximations that
// look slightly off at the extremes.
private const val CORNER_CIRCLE = 1f     // half the side of the 2×2 unit box → a true circle
private const val CORNER_SQUARE = 0.44f  // a generously rounded "squircle", never a hard-edged box

// Two morph oscillators per blob, and this is where the *complexity* the effect needs comes from.
// A single sine would give every blob a metronome: circle, square, circle, square, on a fixed beat,
// and six of them breathing in visible lockstep reads as a screensaver. Summing two waves whose
// periods share no small common factor produces a compound wave that does not repeat for hours —
// so the background is never caught doing the same thing twice, which is the difference between
// "animated" and "alive".
//
// The periods are long on purpose (43s–131s). This is a background: it should be somewhere else by
// the time you look up, not performing while you read.
private val PERIOD_MORPH_A = floatArrayOf(47000f, 61000f, 53000f, 71000f, 43000f, 67000f)
private val PERIOD_MORPH_B = floatArrayOf(113000f, 97000f, 131000f, 89000f, 127000f, 101000f)
private val PHASE_MORPH = floatArrayOf(0.0f, 2.4f, 4.1f, 1.3f, 5.2f, 3.3f)

// A third, slower oscillator gently squashes each blob along one axis (widening it as it narrows,
// so the mass stays constant). Without it the morph is a shape *cycling* between two states; with
// it the shape is never quite either one, which is what stops the round phase from looking like six
// identical circles.
private val PERIOD_SQUASH = floatArrayOf(38000f, 44000f, 50000f, 56000f, 62000f, 68000f)
private const val SQUASH_AMOUNT = 0.07f

// Slow rotation for the squares only, in ms per full turn. Circles don't rotate (it would be
// invisible), so they skip the transform entirely. Alternating signs (below) send neighbouring
// squares spinning opposite ways, which stops the motion from looking mechanically synchronised.
private val PERIOD_ROTATE = floatArrayOf(21000f, 24000f, 27000f, 30000f, 33000f, 36000f)

@Composable
fun FluidGlassBackground(
    palette: List<Color>,
    backdropColor: Color,
    modifier: Modifier = Modifier,
    animated: Boolean = true
) {
    // When the drifting effect is switched off, the background is simply the flat theme colour — no
    // frame clock, no blobs, no offscreen layer. Cheaper and calmer for anyone who prefers stillness.
    if (!animated) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.fillMaxSize().background(backdropColor)
        )
        return
    }

    // On dark backdrops we blend blobs additively so overlaps brighten and fuse into a single
    // glowing shape; on light backdrops plain over-compositing reads better. This also decides
    // whether we need the offscreen layer at all (see class comment).
    val additive = backdropColor.luminance() < 0.5f

    // Hoist the whole *brush* — not just its colour-stop list — out of the per-frame draw (task 7).
    //
    // The previous version rebuilt only the colour list here but still allocated a fresh
    // `Brush.radialGradient` inside the draw loop on every frame, because the gradient's centre and
    // radius moved with the blob. That was the last per-frame allocation left, and at 60fps × 6
    // blobs it is 360 short-lived shader objects a second — steady GC pressure that shows up as
    // micro-stutter exactly when another frame is already under load (opening a detail page, a list
    // fling). Each brush is now built ONCE, at a fixed unit radius centred on the origin, and the
    // draw loop moves/sizes/spins it with a cheap canvas transform instead of rebuilding it. Result:
    // zero allocations per frame in steady state, so the animation holds a smooth frame rate through
    // state transitions. Keyed on the palette so a palette change (including each auto-cycle step)
    // rebuilds them correctly.
    val blobBrushes = remember(palette) {
        Array(BLOB_COUNT) { i ->
            val c = palette[i % palette.size]
            Brush.radialGradient(
                // Reaches out past the box's edge midpoints (radius 1.4 vs the old 1.0) with a gentle
                // three-stop falloff, so the rounded-rect *silhouette itself* is what's visible — the
                // corners no longer sit in fully-transparent gradient. That is the whole reason the
                // square phase now actually reads as a rounded square instead of every blob looking
                // round no matter its corner value. Still a soft glow, not a hard-edged tile, and
                // because the shapes are more filled they now visibly fuse and part as they drift past
                // one another rather than just sliding over each other invisibly.
                colors = listOf(c.copy(alpha = 0.48f), c.copy(alpha = 0.34f), c.copy(alpha = 0f)),
                center = Offset.Zero,
                radius = 1.4f
            )
        }
    }

    // A single monotonic clock, read once per frame. In a @Preview there is no frame clock, so we
    // fall back to a fixed timestamp and render one static frame rather than spinning.
    val inspection = LocalInspectionMode.current
    var elapsedMs by remember { mutableFloatStateOf(0f) }
    if (!inspection) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameNanos { it }
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    elapsedMs = (now - start) / 1_000_000f
                }
            }
        }
    }

    val canvasModifier = modifier
        .fillMaxSize()
        .background(backdropColor)
        // Only pay for an offscreen buffer when additive blending actually needs one. Expressing it
        // as a compositing strategy lets Compose manage and reuse the layer instead of us allocating
        // a screen-sized one by hand every frame, which is what the old saveLayer() did.
        .then(
            if (additive) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            else Modifier
        )

    Canvas(modifier = canvasModifier) {
        val t = elapsedMs
        val minDim = size.minDimension
        val baseRadius = minDim * 0.42f
        val blend = if (additive) BlendMode.Plus else BlendMode.SrcOver

        // Rust fast path (see nativebridge/LucentNative + rust/src/lib.rs): all six blobs' frame
        // parameters — the exact same oscillator formulas, same constants — computed in one native
        // call into a reused buffer, instead of ~30 Kotlin trig evaluations per frame. On devices
        // where the library isn't available the loop below runs its original math unchanged, so
        // the animation is pixel-identical either way; only who does the arithmetic differs.
        val nativeOk = LucentNative.blobFrame(t, size.width, size.height, blobFrameBuf)

        for (i in 0 until BLOB_COUNT) {
            val cx: Float
            val cy: Float
            val radius: Float
            val corner: Float
            val squash: Float
            val angleDeg: Float
            if (nativeOk) {
                val o = i * 6
                cx = blobFrameBuf[o]
                cy = blobFrameBuf[o + 1]
                radius = blobFrameBuf[o + 2]
                corner = blobFrameBuf[o + 3]
                squash = blobFrameBuf[o + 4]
                angleDeg = blobFrameBuf[o + 5]
            } else {
                // angle = 2π * (t / period) + phase. sin/cos are 2π-periodic so this wraps seamlessly and
                // never needs the animation system to "restart" a value — it just keeps counting.
                val ax = TAU * (t / PERIOD_X[i]) + PHASE_X[i]
                val ay = TAU * (t / PERIOD_Y[i]) + PHASE_Y[i]
                // Pulse breathes 0.82 → 1.18 on a reversing curve, matching the old Reverse tween.
                // RepeatMode.Reverse plays forward then backward, so a full up-and-back cycle spans
                // TWICE the tween duration — hence the % (2*period) here, not % period. The raw triangle
                // is passed through smoothstep so it eases in and out at the turnarounds, reproducing the
                // gentle feel of the original FastOutSlowInEasing instead of a hard linear ramp.
                val pulsePhase = (t / (2f * PERIOD_PULSE[i])) % 1f
                val triangle = if (pulsePhase < 0.5f) pulsePhase * 2f else (1f - pulsePhase) * 2f
                val eased = triangle * triangle * (3f - 2f * triangle) // smoothstep
                val pulse = 0.82f + 0.36f * eased

                cx = (BASE_X[i] + AMP_X[i] * cos(ax)) * size.width
                cy = (BASE_Y[i] + AMP_Y[i] * sin(ay)) * size.height
                radius = baseRadius * SIZE_FACTOR[i] * pulse

                // Where this blob currently sits between round and square. Two long sines are summed
                // (weighted, so neither dominates) and mapped to 0..1, then passed through smoothstep —
                // which is what makes the shape *linger* as a circle and *linger* as a square, easing
                // through the in-between states rather than sliding past them at constant speed. A
                // linear ramp here reads as mechanical no matter how long the period is.
                val morphRaw = 0.62f * sin(TAU * (t / PERIOD_MORPH_A[i]) + PHASE_MORPH[i]) +
                    0.38f * sin(TAU * (t / PERIOD_MORPH_B[i]))
                val morph01 = ((morphRaw + 1f) * 0.5f).coerceIn(0f, 1f)
                val morph = morph01 * morph01 * (3f - 2f * morph01) // smoothstep
                corner = CORNER_CIRCLE + (CORNER_SQUARE - CORNER_CIRCLE) * morph

                // Gentle anisotropy: one axis grows as the other shrinks.
                squash = 1f + SQUASH_AMOUNT * sin(TAU * (t / PERIOD_SQUASH[i]) + PHASE_MORPH[i])

                // Every blob spins now, not just the square ones — a rotating circle is invisible, but a
                // shape that is *becoming* a square is not, so the rotation quietly reveals itself as the
                // corners arrive and fades back out as they go. Alternating direction by index parity
                // keeps neighbours from turning in sympathy.
                val dir = if (i % 2 == 0) 1f else -1f
                angleDeg = dir * (t / PERIOD_ROTATE[i]) * 360f
            }

            // Position, size, spin and squash the hoisted unit-brush with a single canvas transform.
            // The brush is fixed at radius 1 around the origin, so translating to the centre and
            // scaling by `radius` reproduces exactly the moving, breathing gradient the old
            // per-frame brush produced — with no allocation. The gradient scales with the matrix, so
            // its soft fade tracks the size (and now the squash) just as before.
            withTransform({
                translate(left = cx, top = cy)
                rotate(degrees = angleDeg, pivot = Offset.Zero)
                scale(scaleX = radius * squash, scaleY = radius / squash, pivot = Offset.Zero)
            }) {
                // One primitive for both shapes: at cornerRadius 1 this 2×2 box is exactly a circle,
                // at 0.44 it is a rounded square, and every value between is a real intermediate
                // shape rather than a cross-fade between two drawings.
                drawRoundRect(
                    brush = blobBrushes[i],
                    topLeft = Offset(-1f, -1f),
                    size = Size(2f, 2f),
                    cornerRadius = CornerRadius(corner, corner),
                    blendMode = blend
                )
            }
        }
    }
}

// Reused frame buffer for the native path: [cx, cy, radius, corner, squash, angleDeg] × 6.
// One allocation for the app's lifetime — the draw loop stays at zero allocations per frame.
// Written and read only on the UI (render) thread inside the Canvas lambda, so no synchronization
// is needed.
private val blobFrameBuf = FloatArray(BLOB_COUNT * 6)

/**
 * Colours for the "Cycle" background option: slowly rotates through every palette in [palettes],
 * cross-fading smoothly from one to the next so the whole background drifts through the full range
 * of colours over time. Each palette is shown for about [secondsPerPalette] seconds before it has
 * fully become the next one, and the loop wraps seamlessly.
 *
 * Returns a colour list the same shape as the palettes passed in (three colours, matching what
 * [FluidGlassBackground] expects), recomputed on each animation frame from the current cross-fade
 * position. Driven by a single frame clock rather than an InfiniteTransition, for the same
 * per-frame-cost reasons as the background itself.
 */
@Composable
fun rememberCyclingPaletteColors(
    palettes: List<List<Color>>,
    secondsPerPalette: Int = 12
): List<Color> {
    if (palettes.isEmpty()) return listOf(Color.White, Color.White, Color.White)
    if (palettes.size == 1) return palettes.first()

    val n = palettes.size
    val totalMs = (n.toLong() * secondsPerPalette * 1000L).toFloat()

    val inspection = LocalInspectionMode.current
    var phase by remember { mutableFloatStateOf(0f) } // 0f..n, wrapping
    if (!inspection) {
        LaunchedEffect(n, secondsPerPalette) {
            val start = withInfiniteAnimationFrameNanos { it }
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    val elapsed = (now - start) / 1_000_000f
                    phase = (elapsed / totalMs * n) % n
                }
            }
        }
    }

    val index = phase.toInt().coerceIn(0, n - 1)
    val frac = (phase - index).coerceIn(0f, 1f)
    val current = palettes[index]
    val next = palettes[(index + 1) % n]
    return current.indices.map { i -> lerp(current[i], next[i % next.size], frac) }
}
