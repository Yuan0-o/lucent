package com.lucent.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.lucent.app.AppScope
import com.lucent.app.data.AppDatabase
import com.lucent.app.tools.TaskActions
import kotlinx.coroutines.launch

/**
 * Handles the action buttons on a reminder notification — right now "Mark as Done" — so a reminder
 * is something you can *act on* from the shade, not just a tap that dumps you into the app to hunt
 * for the task.
 *
 * The work runs through [goAsync]: a broadcast receiver's `onReceive` returns almost immediately, but
 * marking a task done touches the database and reschedules any repeat, so the result is held open
 * until that coroutine finishes and only then released. Completion goes through [TaskActions.complete]
 * — the exact same function the in-app checkbox and the assistant use — so a task completed from the
 * notification behaves identically: it moves to history, its reminder is cancelled, and a repeating
 * task spawns its next occurrence. Finally the notification itself is dismissed, since its reason for
 * existing is now handled.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_DONE) return
        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId < 0) return

        val appContext = context.applicationContext
        val pending = goAsync()
        AppScope.io.launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                db.taskDao().getByIdOnce(taskId)?.let { task ->
                    // Only complete a task that's still open; a double-tap or a stale button is a no-op.
                    if (!task.isDone && task.trashedAt == null) {
                        TaskActions.complete(appContext, db, task)
                    }
                }
                // Dismiss the reminder (its id is the task id — see ReminderReceiver).
                NotificationManagerCompat.from(appContext).cancel(taskId.toInt())
            } catch (_: Throwable) {
                // Nothing actionable from a background receiver; leave the task as-is.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_DONE = "com.lucent.app.action.MARK_TASK_DONE"
    }
}
