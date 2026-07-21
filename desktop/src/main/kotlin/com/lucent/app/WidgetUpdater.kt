package com.lucent.app.widget

import android.content.Context

/**
 * Desktop stub for the Android home-screen widget updater. The desktop has no launcher widgets, so
 * refreshContent is a no-op; it exists so the shared "data changed, refresh the widget" calls
 * compile and run unchanged.
 */
object WidgetUpdater {
    fun refreshContent(context: Context) {
        // no-op on desktop
    }
}
