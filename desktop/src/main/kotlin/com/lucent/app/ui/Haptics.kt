package com.lucent.app.ui

import android.content.Context

/**
 * Desktop twin of the Android Haptics helper. Desktops have no vibrator, so every cue is a no-op;
 * the API survives so AssistantController, TaskStyling, and NoteColors compile verbatim, and the
 * "typing haptics" setting remains a stored (if inert) preference that still round-trips backups.
 */
object Haptics {
    fun tick(context: Context) { /* no vibrator on desktop */ }
    fun typingTick(context: Context) { /* no vibrator on desktop */ }
    fun finishBuzz(context: Context) { /* no vibrator on desktop */ }
}
