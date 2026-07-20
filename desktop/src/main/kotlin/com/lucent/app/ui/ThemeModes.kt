package com.lucent.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Every appearance the app can wear, as one closed list.
 *
 * ### Why this exists (added task 1)
 *
 * The theme used to be three loose string literals — `"system"`, `"light"`, `"dark"` — compared with
 * `==` in `MainActivity`, in `SettingsScreen`, and nowhere else. Adding the four Monet tints to that
 * arrangement would have meant seven string comparisons in two files that must agree with each
 * other forever, and the first typo would be a theme that silently falls back to "system" with no
 * error anywhere. So the list of themes is now a type, and the two things a theme actually decides —
 * *is it a dark theme* and *what colour is the backdrop* — are answered by the theme itself.
 *
 * The stored value is still the plain [key] string, so every existing install keeps its choice and
 * nothing in [com.lucent.app.data.SettingsRepository] had to change: it stores a string, and
 * [fromKey] is lenient about anything it doesn't recognise.
 *
 * ### The Monet tints
 *
 * The four Monet options are *peers* of System/Light/Dark, not a sub-menu — they sit in the same
 * radio list and are chosen the same way. Each one is a light theme whose backdrop is a soft,
 * desaturated wash instead of the neutral off-white: the pale straw of a haystack at dawn, the green
 * of the water-garden, the blue-grey of morning on the Seine, the wisteria over the Giverny bridge.
 *
 * They are deliberately *quiet* — every one sits above 90% lightness. The backdrop is the surface the
 * drifting blobs and all the frosted glass are read against, so a saturated tint here would fight the
 * palette in front of it and drag the contrast of every piece of text down with it. A wash you notice
 * only when you look for it is the right amount for something the whole app sits on top of.
 */
enum class LucentThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    MONET_WHEAT("monet_yellow"),
    MONET_GARDEN("monet_green"),
    MONET_MORNING("monet_blue"),
    MONET_WISTERIA("monet_purple"),
    // Dark peers of the four Monet tints (added later): the same painterly families after dusk —
    // deep, muted, *coloured* darks rather than the neutral near-black of [DARK]. Never true black.
    MONET_NIGHT("monet_night"),
    MONET_PINE("monet_pine"),
    MONET_PLUM("monet_plum"),
    MONET_EMBER("monet_ember");

    // Live i18n lookups (localization task); `label`/`detail` call sites are unchanged.
    val label: String
        get() = when (this) {
            SYSTEM -> com.lucent.app.i18n.S.themeSystem
            LIGHT -> com.lucent.app.i18n.S.themeLight
            DARK -> com.lucent.app.i18n.S.themeDark
            MONET_WHEAT -> com.lucent.app.i18n.S.themeMonetWheat
            MONET_GARDEN -> com.lucent.app.i18n.S.themeMonetGarden
            MONET_MORNING -> com.lucent.app.i18n.S.themeMonetMorning
            MONET_WISTERIA -> com.lucent.app.i18n.S.themeMonetWisteria
            MONET_NIGHT -> com.lucent.app.i18n.S.themeMonetNight
            MONET_PINE -> com.lucent.app.i18n.S.themeMonetPine
            MONET_PLUM -> com.lucent.app.i18n.S.themeMonetPlum
            MONET_EMBER -> com.lucent.app.i18n.S.themeMonetEmber
        }

    val detail: String
        get() = when (this) {
            SYSTEM -> com.lucent.app.i18n.S.themeSystemDesc
            LIGHT -> com.lucent.app.i18n.S.themeLightDesc
            DARK -> com.lucent.app.i18n.S.themeDarkDesc
            MONET_WHEAT -> com.lucent.app.i18n.S.themeMonetWheatDesc
            MONET_GARDEN -> com.lucent.app.i18n.S.themeMonetGardenDesc
            MONET_MORNING -> com.lucent.app.i18n.S.themeMonetMorningDesc
            MONET_WISTERIA -> com.lucent.app.i18n.S.themeMonetWisteriaDesc
            MONET_NIGHT -> com.lucent.app.i18n.S.themeMonetNightDesc
            MONET_PINE -> com.lucent.app.i18n.S.themeMonetPineDesc
            MONET_PLUM -> com.lucent.app.i18n.S.themeMonetPlumDesc
            MONET_EMBER -> com.lucent.app.i18n.S.themeMonetEmberDesc
        }

    /**
     * Whether this theme draws light-on-dark. Only [SYSTEM] consults the device; every other option
     * is an explicit choice and ignores it, which is the entire point of choosing one.
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        DARK -> true
        MONET_NIGHT, MONET_PINE, MONET_PLUM, MONET_EMBER -> true
        else -> false
    }

    /** The flat colour painted behind the drifting blobs. */
    fun backdrop(systemDark: Boolean): Color = when (this) {
        SYSTEM -> if (systemDark) DARK_BACKDROP else LIGHT_BACKDROP
        DARK -> DARK_BACKDROP
        LIGHT -> LIGHT_BACKDROP
        MONET_WHEAT -> Color(0xFFF7F1DC)
        MONET_GARDEN -> Color(0xFFE9F2E5)
        MONET_MORNING -> Color(0xFFE5EDF7)
        MONET_WISTERIA -> Color(0xFFEEE9F7)
        MONET_NIGHT -> Color(0xFF1A2136)
        MONET_PINE -> Color(0xFF16241C)
        MONET_PLUM -> Color(0xFF241A2E)
        MONET_EMBER -> Color(0xFF2A1E19)
    }

    /**
     * The two-colour preview shown beside this option in Settings. For the tints it's the backdrop
     * itself deepened slightly, so the swatch shows the actual colour the app will take on rather
     * than a decorative stand-in.
     */
    fun swatch(systemDark: Boolean): List<Color> = when (this) {
        SYSTEM -> listOf(LIGHT_BACKDROP, DARK_BACKDROP)
        LIGHT -> listOf(LIGHT_BACKDROP, Color(0xFFDCDBE4))
        DARK -> listOf(Color(0xFF23232E), DARK_BACKDROP)
        MONET_WHEAT -> listOf(Color(0xFFFBF7EA), Color(0xFFE8DDB6))
        MONET_GARDEN -> listOf(Color(0xFFF1F7EF), Color(0xFFC9DEC2))
        MONET_MORNING -> listOf(Color(0xFFEEF4FB), Color(0xFFC2D5EA))
        MONET_WISTERIA -> listOf(Color(0xFFF5F1FB), Color(0xFFD3C6EA))
        MONET_NIGHT -> listOf(Color(0xFF2B3552), Color(0xFF141A2C))
        MONET_PINE -> listOf(Color(0xFF25392E), Color(0xFF101B15))
        MONET_PLUM -> listOf(Color(0xFF3A2B48), Color(0xFF1A121F))
        MONET_EMBER -> listOf(Color(0xFF43302A), Color(0xFF1E1512))
    }

    companion object {
        val LIGHT_BACKDROP = Color(0xFFF4F3F8)
        val DARK_BACKDROP = Color(0xFF0E0E14)

        /** Lenient: an unknown or missing key reads as [SYSTEM] rather than throwing. */
        fun fromKey(key: String?): LucentThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
