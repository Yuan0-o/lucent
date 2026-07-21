package com.lucent.app.reminders

import android.content.Context

/**
 * Desktop stub for the Android notification-channel helper. The desktop has no notification channels
 * (an Android concept), so ensureChannel is a no-op; it exists so the shared code that calls it
 * before scheduling a reminder compiles and runs unchanged.
 */
object Notifications {
    fun ensureChannel(context: Context) {
        // no-op on desktop
    }
}
