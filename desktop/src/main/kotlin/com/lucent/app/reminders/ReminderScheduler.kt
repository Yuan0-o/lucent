package com.lucent.app.reminders

import android.content.Context
import com.lucent.app.AppScope
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Task
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop twin of the Android ReminderScheduler.
 *
 * Android arms an exact AlarmManager alarm per task so reminders fire even when the app process is
 * gone. A desktop app has no OS alarm service to hand its wake-ups to, so the honest desktop
 * equivalent is: reminders fire **while Lucent is running** (including minimized to the tray),
 * delivered as a system tray notification. The same three entry points exist with the same
 * semantics — [sync] after every task write, [cancel] on delete, [rescheduleAll] at startup and
 * after a restore — so every shared caller (TaskActions, AppTools, TrashCleanup, BackupManager)
 * compiles verbatim. The Windows work report documents this scope difference explicitly.
 */
object ReminderScheduler {

    /**
     * Set by the desktop shell to a function that shows a tray notification. Kept as a hook rather
     * than a direct Tray reference so this object stays free of compose-desktop types and testable.
     */
    @Volatile var notifier: ((title: String, message: String) -> Unit)? = null

    private val pending = ConcurrentHashMap<Long, Job>()

    /** Whether [task] warrants a pending reminder at all — verbatim Android predicate. */
    private fun shouldFire(task: Task): Boolean {
        val due = task.dueAt ?: return false
        return task.reminderEnabled &&
            !task.isDone &&
            task.trashedAt == null &&
            due > System.currentTimeMillis()
    }

    /**
     * Schedule, reschedule, or clear [task]'s reminder so the timer matches the task's current
     * state. Safe to call after every write, with any task, unconditionally. A due time in the
     * past is "nothing to schedule", never "fire immediately" — same rule as Android.
     */
    fun sync(context: Context, task: Task) {
        cancel(context, task.id)
        if (!shouldFire(task)) return
        val due = task.dueAt ?: return
        val id = task.id
        val title = task.title
        val job = AppScope.io.launch {
            val wait = due - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            pending.remove(id)
            fire(context, id, title)
        }
        pending[id] = job
    }

    /** Cancel any pending reminder for [taskId]. Safe to call even if none was ever scheduled. */
    fun cancel(context: Context, taskId: Long) {
        pending.remove(taskId)?.cancel()
    }

    /**
     * Re-arm every reminder that should be pending, and clear every one that shouldn't. Runs at app
     * start and after a backup import — the two ways a timer can be missing behind the app's back
     * on desktop. Routes each task through [sync], so it is self-correcting like the original.
     */
    suspend fun rescheduleAll(context: Context) {
        val tasks = try {
            AppDatabase.getInstance(context.applicationContext).taskDao().getAllOnce()
        } catch (t: Throwable) {
            return
        }
        tasks.forEach { sync(context, it) }
    }

    private suspend fun fire(context: Context, taskId: Long, scheduledTitle: String) {
        // Re-read the task at fire time: it may have been completed, trashed, or re-dated since the
        // timer was armed (the Android receiver performs the same freshness check).
        val task = try {
            AppDatabase.getInstance(context.applicationContext).taskDao().getByIdOnce(taskId)
        } catch (t: Throwable) {
            null
        }
        if (task != null && !shouldStillNotify(task)) return
        val title = task?.title ?: scheduledTitle
        notifier?.invoke(com.lucent.app.i18n.S.tabTasks, title)
    }

    private fun shouldStillNotify(task: Task): Boolean =
        task.reminderEnabled && !task.isDone && task.trashedAt == null && task.dueAt != null
}
