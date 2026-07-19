package com.lucent.app.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.lucent.app.R

/**
 * The app-wide font choice, stored as a short stable key in settings (see SettingsRepository).
 *
 * "System" keeps Compose's platform font. The rest are bundled TTFs under res/font. Each entry
 * knows its stored [key], the [label] shown in Appearance settings, and how to build its
 * [FontFamily] (null = platform default). Labels are kept to the plain font name so the list is
 * easy to scan.
 *
 * Kept as a small enum rather than free strings so the settings UI, the persisted value, and the
 * Typography builder can never drift out of sync — an unknown stored key simply falls back to
 * [SYSTEM].
 */
enum class LucentFont(val key: String, val label: String, private val family: FontFamily?) {
    SYSTEM("system", "System", null),
    JETBRAINS("jetbrains", "JetBrains Mono", FontFamily(Font(R.font.jetbrainsmono_regular))),
    GREAT_VIBES("greatvibes", "Great Vibes", FontFamily(Font(R.font.greatvibes_regular))),
    CINZEL("cinzel", "Cinzel", FontFamily(Font(R.font.cinzel_regular)));
    // Garamond, Jost and Lora were removed to shrink the bundle (task 10). Their TTFs are deleted
    // and no code references them. Anyone who had one of them selected falls back to SYSTEM, since
    // fromKey() maps every unknown key to the platform font — see below.

    /** The Compose family to apply, or null to leave the platform default in place. */
    val fontFamily: FontFamily? get() = family

    companion object {
        fun fromKey(key: String?): LucentFont = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * A [Typography] with every text style switched to [font]'s family. For the system default we
 * return the stock Typography untouched so Compose uses the platform font exactly as before.
 *
 * We only override the family (not sizes/weights), so all of Material 3's type scale — headline,
 * body, label sizes — is preserved; only the glyphs change.
 */
@Composable
fun lucentTypography(font: LucentFont): Typography {
    val base = Typography()
    val family = font.fontFamily ?: return base
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)
    )
}
