package com.lucent.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Task

/**
 * Schedules and cancels the local alarm behind a task's reminder.
 *
 * ### One entry point
 *
 * [sync] is the only thing callers need. Hand it a task in whatever state it's now in and it works
 * out for itself whether that task *should* have a pending alarm — reminder on, not done, not
 * trashed, and a due time still in the future — and schedules or cancels to match. Every write path
 * (the editor, the assistant's tools, completing a task, trashing it, restoring it, importing a
 * backup) therefore ends with the same one-liner and never has to reason about alarms. There is no
 * "did I remember to cancel this" branch anywhere in the app, because there is nowhere to forget.
 *
 * The alarm is keyed by the task id, used as the PendingIntent request code, so a task has exactly
 * one alarm: rescheduling silently replaces the old one and cancelling is exact.
 *
 * ### Exact vs inexact
 *
 * Exact alarms are used **only when the OS currently allows them** — `canScheduleExactAlarms()` on
 * Android 12+ — and the code falls back to an inexact allow-while-idle alarm otherwise. On Android
 * 14+ the exact-alarm permission is denied by default for a newly installed app like this one, so
 * in practice the fallback is what most installs get, and that is fine: a to-do nudge that arrives
 * a few minutes late under Doze is still a to-do nudge. A user who wants a medication reminder to
 * land on the minute can grant "Alarms & reminders" in system settings and it upgrades itself with
 * no code change and no prompt.
 *
 * What it never does is *require* that permission, or nag for it, or break without it. The
 * SecurityException path is also caught and downgraded rather than crashing, because a handful of
 * OEM builds revoke exact-alarm access between the check and the call, and losing one reminder's
 * punctuality is a far better outcome than crashing the save that scheduled it.
 */
object ReminderScheduler {

    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TASK_TITLE = "task_title"
    private const val ACTION_TASK_REMINDER = "com.lucent.app.action.TASK_REMINDER"

    /**
     * Built identically by [sync] and [cancel] — same action, same target, same request code — so
     * AlarmManager treats them as the same PendingIntent. Extras are *not* part of that identity,
     * which is exactly why [cancel] doesn't need to know the task's title to cancel its alarm.
     */
    private fun pendingIntentFor(context: Context, taskId: Long, title: String, allowCreate: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TASK_REMINDER
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TASK_TITLE, title)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (allowCreate) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, taskId.toInt(), intent, flags)
    }

    /** Whether [task] warrants a pending alarm at all. */
    private fun shouldFire(task: Task): Boolean {
        val due = task.dueAt ?: return false
        return task.reminderEnabled &&
            !task.isDone &&
            task.trashedAt == null &&
            due > System.currentTimeMillis()
    }

    /**
     * Schedule, reschedule, or clear [task]'s reminder so the alarm matches the task's current
     * state. Safe to call after every write, with any task, unconditionally.
     *
     * A due time in the past is treated as "nothing to schedule" rather than "fire immediately": an
     * alarm for a moment that has already been and gone would go off the instant the user hit Save,
     * which reads as a bug, not a reminder.
     */
    fun sync(context: Context, task: Task) {
        val appContext = context.applicationContext
        if (!shouldFire(task)) {
            cancel(appContext, task.id)
            return
        }
        val due = task.dueAt ?: return

        Notifications.ensureChannel(appContext)
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntentFor(appContext, task.id, task.title, allowCreate = true) ?: return

        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
            }
        } catch (t: SecurityException) {
            // Exact-alarm access was revoked between the check and the call, or this OEM restricts
            // it further than stock Android. Fall back rather than take the app down.
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
            } catch (ignored: Throwable) {
                // Nothing further we can do; this one reminder simply won't fire.
            }
        }
    }

    /** Cancel any pending reminder for [taskId]. Safe to call even if none was ever scheduled. */
    fun cancel(context: Context, taskId: Long) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        // FLAG_NO_CREATE: if no alarm exists we get null and there's nothing to cancel, rather than
        // conjuring a PendingIntent solely to throw it away.
        val existing = pendingIntentFor(appContext, taskId, "", allowCreate = false) ?: return
        alarmManager.cancel(existing)
        existing.cancel()
    }

    /**
     * Re-arm every reminder that should be pending, and clear every one that shouldn't.
     *
     * Alarms are OS state, not app data: they don't survive a reboot, an app update, or a force
     * stop, and they are not — and cannot be — part of a backup. So this runs from [BootReceiver],
     * once at app start, and after a backup import, which between them cover every way an alarm can
     * go missing behind the app's back. Because it routes each task through [sync], it is also
     * self-correcting: a task that shouldn't have an alarm gets its stale one cancelled here too.
     */
    suspend fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        db.taskDao().getAllOnce().forEach { sync(appContext, it) }
    }
}
