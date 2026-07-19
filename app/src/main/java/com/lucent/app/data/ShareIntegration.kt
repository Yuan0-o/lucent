package com.lucent.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * System share / intent integration (task 6) — **off by default, and invisible to other apps until
 * the user turns it on.**
 *
 * ### How "off by default" is enforced
 *
 * An `<intent-filter>` declared in the manifest is static — you can't add or remove one at runtime.
 * So the ACTION_SEND filter lives on a separate `<activity-alias>` ([ALIAS_CLASS]) that is declared
 * `android:enabled="false"`. While disabled, the alias does not exist as far as the system's share
 * sheet is concerned, so Lucent simply isn't offered as a share target — exactly the private default
 * a local-first app should ship with. [setEnabled] flips that component on or off with
 * [PackageManager.setComponentEnabledSetting], keyed to the user's toggle, so the app appears in the
 * share sheet only while the setting is on and disappears again the moment it's turned off.
 *
 * The alias targets `MainActivity`, so a shared item is delivered there like any other launch; see
 * `MainActivity.handleShareIntent`.
 */
object ShareIntegration {

    // Namespace-relative to the app's namespace (com.lucent.app), matching the manifest's
    // android:name=".ShareTarget". context.packageName is the installed applicationId, which is the
    // correct package half of the ComponentName; the class half is the namespaced alias name.
    private const val ALIAS_CLASS = "com.lucent.app.ShareTarget"

    private fun aliasComponent(context: Context) = ComponentName(context.packageName, ALIAS_CLASS)

    /** Enable or disable Lucent's presence in the system share sheet. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            context.applicationContext.packageManager.setComponentEnabledSetting(
                aliasComponent(context),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Throwable) {
            // Nothing actionable if the platform refuses; the setting flag remains the source of truth.
        }
    }

    /** A parsed inbound share: some text, and/or a single streamed file. */
    data class Shared(val text: String?, val streamUri: Uri?, val mime: String?)

    /**
     * Pull the shared payload out of an ACTION_SEND intent, or return null if this isn't a share we
     * can turn into a note or task.
     */
    fun parse(intent: Intent?): Shared? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (text.isNullOrBlank() && stream == null) return null
        return Shared(text = text?.takeIf { it.isNotBlank() }, streamUri = stream, mime = intent.type)
    }
}
