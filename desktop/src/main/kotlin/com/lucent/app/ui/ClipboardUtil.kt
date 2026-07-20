package com.lucent.app.ui

import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop twin of the Android clipboard helpers, backed by the AWT system clipboard. Same contract
 * as the original: blank text is a no-op, a successful copy confirms with the localized toast (the
 * haptic tick is a desktop no-op inside Haptics).
 */
fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    } catch (t: Throwable) {
        return // a headless or misbehaving toolkit — fail silently rather than crash
    }
    Haptics.tick(context)
    LucentToast.show(context, com.lucent.app.i18n.S.copiedToast)
}

/**
 * Long-press anywhere on this element to copy [text] to the clipboard — same gesture contract as
 * Android (a plain drag still scrolls; only a held press copies).
 */
fun Modifier.longPressCopy(context: Context, text: String): Modifier =
    pointerInput(text) {
        detectTapGestures(onLongPress = { copyToClipboard(context, text) })
    }
