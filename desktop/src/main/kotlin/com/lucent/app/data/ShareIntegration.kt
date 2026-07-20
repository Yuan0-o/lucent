package com.lucent.app.data

import android.content.Context

/**
 * Desktop twin of the Android ShareIntegration.
 *
 * Android toggles a manifest activity-alias so Lucent appears (or not) in the system share sheet.
 * Windows has no equivalent surface for this app, so the desktop object keeps the API — restore
 * code flips the preference through it — and performs no OS-level work. The Settings screen on
 * desktop describes the toggle honestly as not applicable.
 */
object ShareIntegration {
    fun setEnabled(context: Context, enabled: Boolean) { /* preference only; no OS component on desktop */ }
}
