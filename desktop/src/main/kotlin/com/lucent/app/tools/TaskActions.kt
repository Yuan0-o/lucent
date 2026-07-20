package com.lucent.app.tools

import android.content.Context
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.Note
import com.lucent.app.data.NoteHistory
import com.lucent.app.data.Recurrence
import com.lucent.app.data.RepeatRule
import com.lucent.app.data.Task
import com.lucent.app.reminders.ReminderScheduler

/**
 * The lifecycle actions that have to mean exactly the same thing whether a person did them or the
 * assistant did.
 *
 * Completing a task isn't a one-line write — it has to cancel the reminder, and if the task repeats
 * it has to spawn the next occurrence and arm *that* one's reminder. Trashing a note has to move
 * it, not delete it. Every one of those is a small chain of steps that is easy to get subtly
 * different in two places, and "the checkbox creates next week's occurrence but asking the
 * assistant to tick it off doesn't" is precisely the kind of bug nobody finds for months.
 *
 * So there is one implementation, here, and both callers use it. The UI is not a privileged path
 * and neither is the assistant.
 */
object TaskActions {

    /**
     * Mark [task] complete.
     *
     * Cancels its reminder, and — if it repeats — inserts the next occurrence with the due date
     * advanced past now, every subtask unticked, and its own reminder armed.
     *
     * Pass the task *as it was before* completion: [Recurrence.nextOccurrence] advances from that
     * row's own due date, so handing it the already-completed copy would work from the wrong base.
     *
     * Returns the newly created occurrence, or null if the task doesn't repeat — which lets the
     * caller say "and it'll be back tomorrow" rather than leaving the user to discover it.
     */
    suspend fun complete(context: Context, db: AppDatabase, task: Task): Task? {
        val appContext = context.applicationContext

        db.taskDao().update(task.copy(isDone = true, completedAt = System.currentTimeMillis()))
        ReminderScheduler.cancel(appContext, task.id)

        val next = Recurrence.nextOccurrence(task) ?: return null
        val newId = db.taskDao().insert(next)
        val inserted = next.copy(id = newId)
        ReminderScheduler.sync(appContext, inserted)
        return inserted
    }

    /**
     * Send [task] back to the active list. A task completed early may still have a future due time,
     * so its reminder is re-evaluated rather than assumed dead.
     */
    suspend fun restore(context: Context, db: AppDatabase, task: Task) {
        val restored = task.copy(isDone = false, completedAt = null)
        db.taskDao().update(restored)
        ReminderScheduler.sync(context.applicationContext, restored)
    }

    /**
     * Soft-delete [task]: move it to Trash and silence it. The row and its files stay on disk until
     * the user restores it or the retention sweep purges it — see
     * [com.lucent.app.data.TrashCleanup].
     */
    suspend fun trash(context: Context, db: AppDatabase, task: Task) {
        val trashed = task.copy(trashedAt = System.currentTimeMillis())
        db.taskDao().update(trashed)
        ReminderScheduler.cancel(context.applicationContext, task.id)
    }

    /** Bring [task] back out of the Trash, re-arming its reminder if it still warrants one. */
    suspend fun untrash(context: Context, db: AppDatabase, task: Task) {
        val restored = task.copy(trashedAt = null)
        db.taskDao().update(restored)
        ReminderScheduler.sync(context.applicationContext, restored)
    }

    /** Soft-delete [note]: move it to Trash. Mirrors [trash] so both models behave alike. */
    suspend fun trashNote(db: AppDatabase, note: Note) {
        db.noteDao().update(note.copy(trashedAt = System.currentTimeMillis()))
    }

    /** Bring [note] back out of the Trash, into whichever list it came from (home or archive). */
    suspend fun untrashNote(db: AppDatabase, note: Note) {
        db.noteDao().update(note.copy(trashedAt = null))
    }

    /**
     * Write an edit to [note], capturing its previous text as a revision first.
     *
     * The assistant's `update_note` tool and a version restore both go through here, so an edit made
     * by asking is exactly as recoverable as one made by typing — which is the whole point of
     * having history at all. (The note editor screen does the same two steps inline, because it
     * also has composer state to reconcile.)
     */
    suspend fun updateNoteWithHistory(db: AppDatabase, existing: Note, updated: Note) {
        NoteHistory.recordIfChanged(
            db = db,
            existing = existing,
            newTitle = updated.title,
            newBody = updated.body,
            newTags = updated.tags,
            newIsChecklist = updated.isChecklist,
            newChecklist = updated.checklist
        )
        db.noteDao().update(updated.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * Apply a due date / repeat change to [task] and re-sync its alarm in one step.
     *
     * Clearing the due date also clears the repeat rule, because a task with no due date has no
     * instant to advance from — a repeat rule it can never act on isn't a setting, it's a lie.
     */
    suspend fun setSchedule(
        context: Context,
        db: AppDatabase,
        task: Task,
        dueAt: Long?,
        repeat: RepeatRule?,
        reminderEnabled: Boolean?
    ): Task {
        val rule = repeat ?: RepeatRule.fromKey(task.repeatRule)
        val updated = task.copy(
            dueAt = dueAt,
            repeatRule = if (dueAt == null) RepeatRule.NONE.key else rule.key,
            reminderEnabled = reminderEnabled ?: task.reminderEnabled
        )
        db.taskDao().update(updated)
        ReminderScheduler.sync(context.applicationContext, updated)
        return updated
    }
}
