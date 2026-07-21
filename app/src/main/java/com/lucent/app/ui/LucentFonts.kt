package com.lucent.app.ui

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.lucent.app.data.FontStore

/**
 * How a stored font preference becomes a Compose [FontFamily].
 *
 * The app ships no fonts (font library task). The preference (SettingsRepository.font) holds
 * either [SYSTEM_FONT_KEY] — the platform default, and the out-of-the-box state — or the id of a
 * font the user imported through [FontStore]. This file is the only place that mapping happens:
 * the settings picker previews rows through [LucentFontResolver], and the app-wide Typography is
 * built from the same cache through [lucentTypography], so the two can never disagree.
 *
 * Resolution is defensive by construction: an unknown id, a deleted file, or bytes the platform
 * refuses to parse all resolve to null — the system font — never to a crash or to tofu. That is
 * the same "unknown falls back to system" contract the old bundled-font enum kept, carried over to
 * a world where the ids are user-made.
 */
const val SYSTEM_FONT_KEY = "system"

object LucentFontResolver {

    // getOrPut needs a non-null value even for "load failed", hence the tiny holder. Caching a
    // failure is correct here: ids are minted once per import and never reused, so a file that
    // failed to load will not silently become loadable under the same id.
    private class Holder(val family: FontFamily?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Holder>()

    /**
     * The [FontFamily] for a stored font key, or null for the platform default. Null is returned
     * for [SYSTEM_FONT_KEY], for blank/unknown keys, and for any font that cannot be loaded.
     *
     * The first resolution of an id reads the [FontStore] manifest and points a [Font] at the
     * imported file; afterwards it is a map hit, so this is cheap enough to call from composition
     * (the settings picker draws every row in its own face through this).
     */
    fun resolve(context: Context, fontKey: String?): FontFamily? {
        if (fontKey.isNullOrBlank() || fontKey == SYSTEM_FONT_KEY) return null
        return cache.getOrPut(fontKey) {
            try {
                val file = FontStore.fontFile(context.applicationContext, fontKey)
                    ?: return@getOrPut Holder(null)
                Holder(FontFamily(Font(file)))
            } catch (_: Throwable) {
                Holder(null)
            }
        }.family
    }

    /** Drop one cached family — call when its font is deleted, so the file's memory can go too. */
    fun evict(fontKey: String) {
        cache.remove(fontKey)
    }

    /** Drop every cached family — call when wiping all data. */
    fun evictAll() {
        cache.clear()
    }
}

/**
 * A [Typography] with every text style switched to the family [fontKey] resolves to. For the
 * system default (or anything that fails to resolve) we return the stock Typography untouched so
 * Compose uses the platform font exactly as before.
 *
 * We only override the family (not sizes/weights), so all of Material 3's type scale — headline,
 * body, label sizes — is preserved; only the glyphs change.
 */
@Composable
fun lucentTypography(fontKey: String): Typography {
    val context = LocalContext.current
    val base = Typography()
    val family = remember(fontKey) { LucentFontResolver.resolve(context, fontKey) } ?: return base
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
