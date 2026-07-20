package com.lucent.app.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

/**
 * Desktop twin of the Android LucentFonts.
 *
 * Same twelve bundled typefaces, same keys, same native-name labels, same grouped picker data —
 * the identical TTF files ship in the desktop resources under /fonts. The only adaptation is how a
 * [FontFamily] is built: Android references generated font resources; desktop loads the bytes from
 * the classpath through Compose's desktop `Font(identity, data)` constructor. Families are built
 * lazily and cached so the twelve files are only read when actually selected/previewed, and a
 * missing resource degrades to the platform font instead of crashing.
 */
enum class FontScript { SYSTEM, LATIN, CHINESE, JAPANESE, KOREAN }

enum class LucentFont(
    val key: String,
    val label: String,
    val script: FontScript,
    private val resource: String?
) {
    SYSTEM("system", "System", FontScript.SYSTEM, null),

    // ---- Latin / English (three faces) ----
    JETBRAINS("jetbrains", "JetBrains Mono", FontScript.LATIN, "jetbrainsmono_regular.ttf"),
    GREAT_VIBES("greatvibes", "Great Vibes", FontScript.LATIN, "greatvibes_regular.ttf"),
    CINZEL("cinzel", "Cinzel", FontScript.LATIN, "cinzel_regular.ttf"),

    // ---- CJK families — three faces per script, labelled in their own native names ----
    NOTO_SERIF_SC("notoserifsc", "\u601d\u6e90\u5b8b\u4f53", FontScript.CHINESE, "notoserifsc_regular.ttf"),
    ZCOOL_XIAOWEI("zcoolxiaowei", "\u7ad9\u9177\u5c0f\u8587", FontScript.CHINESE, "zcoolxiaowei_regular.ttf"),
    ZHI_MANG_XING("zhimangxing", "\u5fd7\u83bd\u884c\u4e66", FontScript.CHINESE, "zhimangxing_regular.ttf"),
    SHIPPORI_MINCHO("shipporimincho", "\u3057\u3063\u307d\u308a\u660e\u671d", FontScript.JAPANESE, "shipporimincho_regular.ttf"),
    YUJI_MAI("yujimai", "\u4f51\u5b57\u821e", FontScript.JAPANESE, "yujimai_regular.ttf"),
    REGGAE_ONE("reggaeone", "\u30ec\u30b2\u30a8 One", FontScript.JAPANESE, "reggaeone_regular.ttf"),
    SONG_MYUNG("songmyung", "\uc1a1\uba85", FontScript.KOREAN, "songmyung_regular.ttf"),
    NANUM_BRUSH("nanumbrush", "\ub098\ub214\uc190\uae00\uc528 \ubd93", FontScript.KOREAN, "nanumbrushscript_regular.ttf"),
    EAST_SEA_DOKDO("eastseadokdo", "\ub3d9\ud574 \ub3c5\ub3c4", FontScript.KOREAN, "eastseadokdo_regular.ttf");

    /** The Compose family to apply, or null to leave the platform default in place. */
    val fontFamily: FontFamily?
        get() {
            val res = resource ?: return null
            return cache.getOrPut(key) {
                try {
                    val bytes = LucentFont::class.java.getResourceAsStream("/fonts/$res")
                        ?.use { it.readBytes() }
                        ?: return@getOrPut Holder(null)
                    Holder(FontFamily(Font(identity = key, data = bytes)))
                } catch (t: Throwable) {
                    Holder(null)
                }
            }.family
        }

    // getOrPut needs a non-null value even for "load failed", hence the tiny holder.
    private class Holder(val family: FontFamily?)

    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Holder>()

        fun fromKey(key: String?): LucentFont = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * A [Typography] with every text style switched to [font]'s family — verbatim from Android: only
 * the family is overridden, so Material 3's full type scale is preserved.
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
