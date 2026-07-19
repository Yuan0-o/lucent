package com.lucent.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Put [text] on the system clipboard and confirm with a short toast + haptic tick. No-op for blank text. */
fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Lucent", text))
    // Tactile confirmation — copying is a valid interaction and the task calls it out specifically.
    Haptics.tick(context)
    LucentToast.show(context, "Copied")
}

/**
 * Long-press anywhere on this element to copy [text] to the clipboard. Uses a tap-gesture
 * detector (not combinedClickable) so it adds no tap ripple and doesn't fight the parent list's
 * vertical scroll — a plain drag still scrolls, only a held press triggers the copy.
 */
fun Modifier.longPressCopy(context: Context, text: String): Modifier =
    this.pointerInput(text) {
        detectTapGestures(onLongPress = { copyToClipboard(context, text) })
    }
