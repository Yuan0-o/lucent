package com.lucent.app.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.lucent.app.R

/**
 * Which writing system a bundled font is designed for. Drives the grouped, language-labelled font
 * picker (see SettingsScreen): every font is shown, sorted under the header for its script, so the
 * user can tell at a glance which language each face is meant for. [SYSTEM] is the platform default.
 */
enum class FontScript { SYSTEM, LATIN, CHINESE, JAPANESE, KOREAN }

/**
 * The app-wide font choice, stored as a short stable key in settings (see SettingsRepository).
 *
 * "System" keeps Compose's platform font. The rest are bundled TTFs under res/font. Each entry
 * knows its stored [key], the [label] shown in the picker, which [script] it belongs to, and how to
 * build its [FontFamily] (null = platform default).
 *
 * The label is the font's OWN native name (思源宋体, しっぽり明朝, 송명, …) rather than a romanization,
 * so a reader of that language recognizes it immediately and the picker reads elegantly in any of
 * the four UI languages. The picker draws each label in its own font, so the list also previews it.
 *
 * Kept as a small enum rather than free strings so the settings UI, the persisted value, and the
 * Typography builder can never drift out of sync — an unknown stored key simply falls back to
 * [SYSTEM].
 */
enum class LucentFont(
    val key: String,
    val label: String,
    val script: FontScript,
    private val family: FontFamily?
) {
    SYSTEM("system", "System", FontScript.SYSTEM, null),

    // ---- Latin / English (three faces) ----
    JETBRAINS("jetbrains", "JetBrains Mono", FontScript.LATIN, FontFamily(Font(R.font.jetbrainsmono_regular))),
    GREAT_VIBES("greatvibes", "Great Vibes", FontScript.LATIN, FontFamily(Font(R.font.greatvibes_regular))),
    CINZEL("cinzel", "Cinzel", FontScript.LATIN, FontFamily(Font(R.font.cinzel_regular))),

    // ---- CJK families (localization task) ----
    //
    // Added exactly the way the Latin fonts above are: a bundled TTF under res/font, one enum entry,
    // and nothing else — the picker, persistence, and Typography builder all work off this list.
    // Three faces per script, so each of Chinese, Japanese, and Korean gets the same choice English
    // has, and each label is the font's own native name.
    //
    // Coverage note: none of these is a pan-CJK face (e.g. the Japanese fonts carry kana + the kanji
    // set but no Hangul). That is safe: Compose resolves missing glyphs through the platform's
    // per-glyph fallback chain, so text in another script renders in the system font rather than as
    // tofu — nothing is ever blocked or shown as □.
    NOTO_SERIF_SC("notoserifsc", "思源宋体", FontScript.CHINESE, FontFamily(Font(R.font.notoserifsc_regular))),
    ZCOOL_XIAOWEI("zcoolxiaowei", "站酷小薇", FontScript.CHINESE, FontFamily(Font(R.font.zcoolxiaowei_regular))),
    ZHI_MANG_XING("zhimangxing", "志莽行书", FontScript.CHINESE, FontFamily(Font(R.font.zhimangxing_regular))),
    SHIPPORI_MINCHO("shipporimincho", "しっぽり明朝", FontScript.JAPANESE, FontFamily(Font(R.font.shipporimincho_regular))),
    YUJI_MAI("yujimai", "佑字舞", FontScript.JAPANESE, FontFamily(Font(R.font.yujimai_regular))),
    REGGAE_ONE("reggaeone", "レゲエ One", FontScript.JAPANESE, FontFamily(Font(R.font.reggaeone_regular))),
    SONG_MYUNG("songmyung", "송명", FontScript.KOREAN, FontFamily(Font(R.font.songmyung_regular))),
    NANUM_BRUSH("nanumbrush", "나눔손글씨 붓", FontScript.KOREAN, FontFamily(Font(R.font.nanumbrushscript_regular))),
    EAST_SEA_DOKDO("eastseadokdo", "동해 독도", FontScript.KOREAN, FontFamily(Font(R.font.eastseadokdo_regular)));
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
