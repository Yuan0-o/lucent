package com.lucent.app.reminders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The notification plumbing for task reminders, kept small and side-effect-light so
 * [ensureChannel] is safe to call from anywhere, as often as you like.
 */
object Notifications {

    const val CHANNEL_ID = "task_reminders"
    private const val CHANNEL_NAME = "Task reminders"
    private const val CHANNEL_DESC = "Alerts you when a task with a reminder reaches its due time"

    /**
     * Create the reminder channel if it isn't there already. Idempotent and cheap — creating a
     * channel that exists is a no-op at the platform level, and below Oreo there are no channels at
     * all — so callers never need a "have I done this yet" flag.
     */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(CHANNEL_NAME)
            .setDescription(CHANNEL_DESC)
            .setVibrationEnabled(true)
            .build()
        NotificationManagerCompat.from(context.applicationContext).createNotificationChannel(channel)
    }

    /**
     * Whether we're currently allowed to post a notification. Always true below Android 13, where
     * POST_NOTIFICATIONS isn't a runtime permission at all.
     *
     * Note what this is *not* used for: it never gates *scheduling*. A user who declines the
     * permission still gets their reminder stored on the task, and the moment they grant it later
     * the reminder starts working — rather than the app having silently thrown the preference away
     * because of an answer they gave once, months ago.
     */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
