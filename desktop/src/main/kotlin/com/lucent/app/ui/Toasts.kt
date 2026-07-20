package com.lucent.app.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop twin of the Android LucentToast.
 *
 * Android delegates to the platform Toast. The desktop shell renders these itself: [show] posts a
 * message onto [messages], and DesktopApp draws the newest entry as a floating glass chip that
 * fades after [SHORT_MS]/[LONG_MS]. The call-site API is identical, so shared code that toasts
 * ("copied", "restored", import summaries) works unchanged.
 */
object LucentToast {

    const val SHORT_MS = 2200L
    const val LONG_MS = 4200L

    data class Entry(val id: Long, val message: String, val longDuration: Boolean)

    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    private val _messages = MutableStateFlow<Entry?>(null)
    val messages: StateFlow<Entry?> = _messages

    fun show(context: Context, message: String, longDuration: Boolean = false) {
        _messages.value = Entry(counter.incrementAndGet(), message, longDuration)
    }

    /** Called by the overlay once an entry has finished displaying. */
    fun clear(entry: Entry) {
        if (_messages.value?.id == entry.id) _messages.value = null
    }
}
