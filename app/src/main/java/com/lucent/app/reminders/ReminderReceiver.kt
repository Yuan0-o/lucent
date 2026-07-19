package com.lucent.app.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lucent.app.MainActivity
import com.lucent.app.R

/**
 * Fires when a scheduled task reminder comes due (see [ReminderScheduler]) and posts the
 * notification.
 *
 * The title is read straight out of the Intent extras rather than re-queried from the database.
 * That's deliberate: a broadcast receiver has a few seconds to do its work, and making the
 * reminder's arrival depend on Room being ready to answer a query at that exact moment would mean
 * the one path that absolutely must not fail is the one with a database on its critical path. The
 * only cost is that a task renamed after its alarm was set could show the older title — and
 * [ReminderScheduler.sync] runs on every save, which rewrites the PendingIntent's extras, so in
 * practice even that doesn't happen.
 *
 * The notification id is the task id, so a re-fired reminder replaces its own notification instead
 * of stacking duplicates.
 */
class ReminderReceiver : BroadcastReceiver() {

    private companion object {
        // Offsets the "Mark as Done" PendingIntent's request code away from the content intent's
        // (which uses the raw task id) so the two never overwrite each other.
        const val DONE_REQUEST_OFFSET = 1_000_000
    }

    @SuppressLint("MissingPermission") // Guarded by Notifications.canPost() immediately below.
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId < 0) return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Untitled task"

        Notifications.ensureChannel(context)
        if (!Notifications.canPost(context)) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Mark as Done" action button: completes the task straight from the shade via
        // NotificationActionReceiver, without opening the app. A distinct request code (offset from
        // the content-intent's) keeps the two PendingIntents from colliding.
        val doneIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_DONE
            putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt() + DONE_REQUEST_OFFSET,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Task due")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_notification, "Mark as Done", donePendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(taskId.toInt(), notification)
        } catch (t: SecurityException) {
            // The permission was revoked between the check above and posting. Nothing to do.
        }
    }
}
