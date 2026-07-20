package com.lucent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val LocalHazeState = compositionLocalOf { HazeState() }
val LocalOnGradient = compositionLocalOf { Color.White }
val LocalOnGradientMuted = compositionLocalOf { Color.White.copy(alpha = 0.65f) }

/**
 * How much space the floating bottom capsule (plus the gap it keeps above the system nav bar)
 * occupies, published so every scrollable region can reserve exactly that much at its bottom.
 *
 * ### Why this exists (the "capsule floats over content" change)
 *
 * The bottom capsule used to sit in a *reserved strip*: the Scaffold inset all tab content by the
 * bar's full height, so nothing was ever drawn behind the pill — it hovered over a band of blank
 * background, which is what made it read as a docked bar rather than a floating piece of glass. The
 * app content now extends to the very bottom edge and passes *under* the capsule, so the pill's
 * blur samples real cards and text sliding beneath it (that is the whole point of the glass).
 *
 * The cost of that is nothing reserves the space any more, so a list's last row — or a form's last
 * field, or the assistant's input bar — would end up *trapped behind* the pill. Each scrollable
 * root reads this value and pads its own bottom by it (a list as `contentPadding`, a scrolled
 * column as trailing padding, the chat column as bottom padding that lifts the input bar), so the
 * last thing the user needs to reach always clears the capsule while everything above it still
 * scrolls freely underneath. One source of truth, set once in [com.lucent.app.MainActivity] from
 * the Scaffold's own measured bottom inset, so it can never drift from the pill's real height.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/**
 * Shared tuning for every glass surface in the app — cards, the bottom capsule, the pill buttons.
 *
 * These live in one place because the whole point of the material is that it looks like *one*
 * material. When the capsule's fill and a card's fill were tuned separately they drifted, and the
 * app ended up with two different kinds of glass sitting next to each other.
 *
 * ### Two attempts, and what they each got wrong
 *
 * **Milk.** The first light-theme material was white at 42% under a white sheen at 34% — roughly
 * two-thirds of an opaque white coat. On a pale backdrop that is white on white: nothing behind it
 * survived, so there was no edge, no depth, and no evidence of anything being translucent at all.
 *
 * **Dirt.** The second went the opposite way: a near-black tint at 15%. It fixed the edge, and
 * introduced a worse problem. A dark wash over a colourful background doesn't just darken it, it
 * *desaturates* it — the drifting palette turned grey-green, the top bar became a slab, and the whole
 * light theme looked like it needed cleaning. Grey is what you get when you mix a colour with its own
 * absence, and there is no amount of it that looks deliberate.
 *
 * ### What actually makes glass read as glass
 *
 * Neither brightness nor darkness. Real glass over a light surface is defined almost entirely by its
 * **boundary** — a bright catch along the lit edge, a faint dark line where it meets what is behind
 * it — and by colour that comes through essentially intact. The fill's job is only to lift the
 * surface slightly: a hint, not a coat.
 *
 * So the material spends its budget on the edge instead of the fill:
 *
 *  - the fill is **light on both themes** and very transparent (≤15%), so the palette keeps its
 *    colour, its saturation, and — crucially — its *variation* across the surface, which is the one
 *    thing an opaque panel can never fake;
 *  - the rim is a **gradient**, bright at the top and shading to a dark hairline at the bottom, which
 *    is what a curved lit edge does — and which is a border, not another layer.
 *
 * What it deliberately does *not* do is stack a sheen or a shadow on top. See [frostedGlass] for
 * what that cost.
 */
object LucentGlass {
    /**
     * Haze container colour, per theme. Passed to [dev.chrisbanes.haze.materials.HazeMaterials] so
     * the blur is tinted with the theme's own backdrop rather than an arbitrary colour — a blur
     * tinted with the surface it sits on has no visible boundary of its own, which is exactly what
     * you want from a top bar. Tinting it with near-black is what turned the light theme's top bar
     * into a grey slab with a hard edge across the screen.
     */
    val HazeContainerDark = Color(0xFF0E0E14)
    val HazeContainerLight = Color(0xFFF6F5FA)

    /** Fill alpha for an untinted card: a lift, not a coat. */
    const val CARD_FILL_DARK = 0.09f
    const val CARD_FILL_LIGHT = 0.15f

    /**
     * Fill alpha for surfaces that *also* carry a Haze blur (the capsule, the pill buttons). They
     * need far less of their own fill than a card does, because the blur has already laid down a
     * tint of its own — stacking a heavy fill on top of it is what made them opaque.
     */
    const val BLURRED_FILL_DARK = 0.06f
    const val BLURRED_FILL_LIGHT = 0.14f

    /**
     * Fill for the floating bottom navigation capsule.
     *
     * Lighter than the card fills, and deliberately so. A card is a page of content you read ON the
     * glass, so it can afford a fill that lifts it clearly off the background. The nav capsule is a
     * small object floating OVER the page, and its whole claim to being glass is that you can see
     * what is behind it — including, in the case that prompted this, a red button on the Data page.
     * It carries no blur, no sheen and no gloss (see the bottomBar comment in MainActivity), so this
     * fill plus the rim is the entire material; anything heavier here and the capsule goes back to
     * being a plate.
     */
    const val NAV_FILL_DARK = 0.07f
    const val NAV_FILL_LIGHT = 0.11f
}

/** True when the current theme draws light-on-dark. */
@Composable
fun isDarkGlass(): Boolean = LocalOnGradient.current.luminance() > 0.5f

/**
 * The rim of a glass surface: bright along the top edge, fading to a faint dark hairline at the
 * bottom. On a light theme this is most of what makes the pane visible at all, so it carries more
 * contrast there than on dark, where the fill already separates the surface from the backdrop.
 */
@Composable
fun lucentGlassRim(strong: Boolean = false): Brush {
    val dark = isDarkGlass()
    val ink = LocalOnGradient.current
    return if (dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.42f else 0.32f),
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.08f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.95f else 0.90f),
                ink.copy(alpha = 0.07f),
                ink.copy(alpha = if (strong) 0.24f else 0.20f)
            )
        )
    }
}

/**
 * The shared frosted-glass surface used throughout the app.
 *
 * ### Third attempt, and this one subtracts instead of adding
 *
 * The first version was white at 42% under a white sheen at 34% — milk. The second was a near-black
 * tint — dirt. The third added a **drop shadow** on top of a fill *and* a sheen, and produced a
 * visible pale rectangle inside every card: `Modifier.shadow` creates a `graphicsLayer`, and every
 * one of these cards lives inside a container marked `hazeSource` (that is how the blur behind the
 * bars is captured). A child layer inside a capture layer is exactly the arrangement that composites
 * wrongly, and it did — a hard-edged block, on every card, everywhere.
 *
 * The pattern across all three is the same mistake made three ways: each attempt tried to *add*
 * another coat — more white, more black, more shadow — to a surface whose entire job is to let you
 * see through it. So this one goes the other way and takes things away.
 *
 * What is left is the smallest thing that can still read as glass:
 *
 *  - **A fill so light it is barely there** (15% white). Its only job is to lift the surface a
 *    fraction; the drifting colour behind it comes through with its own variation intact, and that
 *    variation *is* the evidence of transparency. A flat panel cannot show the background moving
 *    through it.
 *  - **One hairline rim, shaded top to bottom.** Bright along the top edge where light would catch a
 *    curved pane, fading to a faint dark line along the bottom where it would sit closest to what is
 *    behind it. That single gradient does the work the sheen and the shadow were both failing to do,
 *    and it is a border — it creates no layer, so it cannot fight the blur capture.
 *
 * No sheen overlay. No shadow. No second background. Two draws and an edge.
 */
fun Modifier.frostedGlass(cornerRadius: Dp = 20.dp, tint: Color = Color.White): Modifier = composed {
    val shape = RoundedCornerShape(cornerRadius)
    val darkTheme = isDarkGlass()
    val tinted = tint != Color.White

    val fillColor = if (tinted) tint else Color.White
    val fillAlpha = when {
        tinted && darkTheme -> 0.20f
        tinted -> 0.26f
        darkTheme -> LucentGlass.CARD_FILL_DARK
        else -> LucentGlass.CARD_FILL_LIGHT
    }

    clip(shape)
        .background(fillColor.copy(alpha = fillAlpha))
        .then(
            if (tinted) Modifier.border(1.dp, tint.copy(alpha = if (darkTheme) 0.45f else 0.55f), shape)
            else Modifier.border(1.dp, lucentGlassRim(), shape)
        )
}

private val timestampFormatter get() = com.lucent.app.i18n.LDates.of(com.lucent.app.i18n.S.patternTimestamp)

fun formatTimestamp(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return zoned.format(timestampFormatter)
}

// Date-only formatter used by the date-search chips (no time of day, since the filter matches a
// whole calendar day rather than a specific instant).
private val dateFormatter get() = com.lucent.app.i18n.LDates.of(com.lucent.app.i18n.S.patternDateFull)

fun formatDate(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return zoned.format(dateFormatter)
}

/**
 * True when [a] and [b] fall on the same calendar day in the device's local time zone. The
 * "today" home section uses this. Compares by local date rather than by a fixed 24h window so
 * daylight-saving transitions can't bump an item into the neighbouring day.
 */
fun sameLocalDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

/**
 * True when [itemMillis] falls on a calendar day within the inclusive range [[startMillis],
 * [endMillis]] in the device's local time zone. The date-search filter is a *range* now (start and
 * end date), so this replaces the old single-day match. Selecting the same day for both ends matches
 * exactly that one day. Compared by local date on both ends so a partial-day timestamp still counts.
 */
fun withinLocalDayRange(itemMillis: Long, startMillis: Long, endMillis: Long): Boolean {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(itemMillis).atZone(zone).toLocalDate()
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
    // Guard against a caller passing the ends the wrong way round.
    val lo = if (start.isAfter(end)) end else start
    val hi = if (start.isAfter(end)) start else end
    return !day.isBefore(lo) && !day.isAfter(hi)
}

/** A compact label for a date range, collapsing a same-day range to a single date. */
fun formatDateRange(startMillis: Long, endMillis: Long): String {
    val start = formatDate(startMillis)
    val end = formatDate(endMillis)
    return if (start == end) start else "$start – $end"
}

/** Which section a palette is shown under in the appearance picker. */
enum class PaletteGroup { SOLID, GRADIENT, CLASSIC }

/**
 * Stored palette value for the auto-cycling option, which slowly rotates through every palette
 * over time. It isn't a [LucentPalette] entry because it doesn't have fixed colours; MainActivity
 * recognises this value and animates the background instead (see rememberCyclingPaletteColors).
 */
const val PALETTE_CYCLE = "CYCLE"

/**
 * A background palette: three colours the fluid-glass blobs draw from. [group] only decides which
 * heading it appears under in Settings.
 *
 *  - SOLID palettes are three tonal shades of a single hue, so they read as one elegant colour.
 *  - GRADIENT palettes mix distinct hues for a vivid multi-colour wash.
 *  - CLASSIC are the original palettes, kept so existing choices (and the "SUNSET" default) stay valid.
 *
 * Every palette has exactly three colours so the cycling option can cross-fade between any two of
 * them element by element.
 */
enum class LucentPalette(val colors: List<Color>, val group: PaletteGroup) {
    // ---- Classic (original) ----
    SUNSET(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)), PaletteGroup.CLASSIC),
    OCEAN(listOf(Color(0xFF00507A), Color(0xFF3A6EA5), Color(0xFF4FD9C4)), PaletteGroup.CLASSIC),
    FOREST(listOf(Color(0xFF0F4C3A), Color(0xFF1F9E6B), Color(0xFFB6E388)), PaletteGroup.CLASSIC),
    BERRY(listOf(Color(0xFF5B2C82), Color(0xFFB43D8F), Color(0xFFFF7CA3)), PaletteGroup.CLASSIC),
    MIDNIGHT(listOf(Color(0xFF16213E), Color(0xFF0F3460), Color(0xFF533483)), PaletteGroup.CLASSIC),

    // ---- Solid (elegant single-hue) ----
    BLUSH(listOf(Color(0xFF7A2E43), Color(0xFFC96A80), Color(0xFFF3B8C6)), PaletteGroup.SOLID),
    LAVENDER(listOf(Color(0xFF4B3A6B), Color(0xFF8A6FB0), Color(0xFFCBB6E8)), PaletteGroup.SOLID),
    SAGE(listOf(Color(0xFF2F4A3C), Color(0xFF5E8B6F), Color(0xFFAFCBB4)), PaletteGroup.SOLID),
    SAND(listOf(Color(0xFF7A5C36), Color(0xFFC1996A), Color(0xFFEAD6B8)), PaletteGroup.SOLID),
    SLATE(listOf(Color(0xFF2A3A4A), Color(0xFF4F6B84), Color(0xFF9DB4C8)), PaletteGroup.SOLID),
    TERRACOTTA(listOf(Color(0xFF7A3B2E), Color(0xFFC26A50), Color(0xFFEAB59B)), PaletteGroup.SOLID),
    TEAL(listOf(Color(0xFF10403B), Color(0xFF2E7E76), Color(0xFF8FC9C0)), PaletteGroup.SOLID),

    // ---- Gradient (vivid multi-hue) ----
    AURORA(listOf(Color(0xFF0FA3A3), Color(0xFF6A5AE0), Color(0xFFE85D9E)), PaletteGroup.GRADIENT),
    PEACH_DUSK(listOf(Color(0xFFFF8A5B), Color(0xFFEE4D8F), Color(0xFF8A4FD8)), PaletteGroup.GRADIENT),
    COSMIC(listOf(Color(0xFF1E5AE8), Color(0xFF9B2FE8), Color(0xFF2ED0C0)), PaletteGroup.GRADIENT);

    // Live i18n lookup (localization task); call sites keep reading `palette.label`.
    val label: String
        get() = when (this) {
            SUNSET -> com.lucent.app.i18n.S.paletteSunset
            OCEAN -> com.lucent.app.i18n.S.paletteOcean
            FOREST -> com.lucent.app.i18n.S.paletteForest
            BERRY -> com.lucent.app.i18n.S.paletteBerry
            MIDNIGHT -> com.lucent.app.i18n.S.paletteMidnight
            BLUSH -> com.lucent.app.i18n.S.paletteBlush
            LAVENDER -> com.lucent.app.i18n.S.paletteLavender
            SAGE -> com.lucent.app.i18n.S.paletteSage
            SAND -> com.lucent.app.i18n.S.paletteSand
            SLATE -> com.lucent.app.i18n.S.paletteSlate
            TERRACOTTA -> com.lucent.app.i18n.S.paletteTerracotta
            TEAL -> com.lucent.app.i18n.S.paletteTeal
            AURORA -> com.lucent.app.i18n.S.paletteAurora
            PEACH_DUSK -> com.lucent.app.i18n.S.palettePeachDusk
            COSMIC -> com.lucent.app.i18n.S.paletteCosmic
        }
}
