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
enum class LucentThemeMode(val key: String, val label: String, val detail: String) {
    SYSTEM("system", "System default", "Follow the device's light/dark setting"),
    LIGHT("light", "Light", "The neutral pale backdrop"),
    DARK("dark", "Dark", "The near-black backdrop"),
    MONET_WHEAT("monet_yellow", "Monet wheat", "Pale straw — a light theme"),
    MONET_GARDEN("monet_green", "Monet garden", "Pale water-garden green — a light theme"),
    MONET_MORNING("monet_blue", "Monet morning", "Pale morning blue — a light theme"),
    MONET_WISTERIA("monet_purple", "Monet wisteria", "Pale wisteria — a light theme");

    /**
     * Whether this theme draws light-on-dark. Only [SYSTEM] consults the device; every other option
     * is an explicit choice and ignores it, which is the entire point of choosing one.
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        DARK -> true
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
    }

    companion object {
        val LIGHT_BACKDROP = Color(0xFFF4F3F8)
        val DARK_BACKDROP = Color(0xFF0E0E14)

        /** Lenient: an unknown or missing key reads as [SYSTEM] rather than throwing. */
        fun fromKey(key: String?): LucentThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
