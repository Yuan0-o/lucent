package com.lucent.app.data

import java.util.Calendar

/**
 * The repeat cadence a task can be set to. Stored on [Task.repeatRule] as [key].
 *
 * [fromKey] is lenient in the same way [TaskPriority.fromKey] is, because the assistant may pass
 * "every week" or "annually" when it means WEEKLY / YEARLY.
 */
enum class RepeatRule(val key: String, val label: String) {
    NONE("NONE", "Does not repeat"),
    DAILY("DAILY", "Daily"),
    WEEKLY("WEEKLY", "Weekly"),
    MONTHLY("MONTHLY", "Monthly"),
    YEARLY("YEARLY", "Yearly");

    companion object {
        fun fromKey(key: String?): RepeatRule {
            val k = key?.trim()?.lowercase() ?: return NONE
            entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.let { return it }
            return when (k) {
                "day", "daily", "everyday", "every day" -> DAILY
                "week", "every week" -> WEEKLY
                "month", "every month" -> MONTHLY
                "year", "annual", "annually", "every year" -> YEARLY
                else -> NONE
            }
        }
    }
}

/**
 * Recurring-task support. When a task whose [Task.repeatRule] isn't [RepeatRule.NONE] is completed,
 * the active list gets a fresh copy of it with the due date advanced — this is what makes "Take
 * vitamins" reappear tomorrow instead of disappearing into the completed history forever.
 */
object Recurrence {

    /**
     * Advance [fromMillis] by exactly one step of [rule]. [Calendar] absorbs the month-length and
     * leap-year edges for us (Jan 31 + 1 month lands on the last valid day of February, not an
     * invalid Feb 31).
     */
    fun advance(fromMillis: Long, rule: RepeatRule): Long {
        if (rule == RepeatRule.NONE) return fromMillis
        val cal = Calendar.getInstance().apply { timeInMillis = fromMillis }
        when (rule) {
            RepeatRule.DAILY -> cal.add(Calendar.DAY_OF_MONTH, 1)
            RepeatRule.WEEKLY -> cal.add(Calendar.DAY_OF_MONTH, 7)
            RepeatRule.MONTHLY -> cal.add(Calendar.MONTH, 1)
            RepeatRule.YEARLY -> cal.add(Calendar.YEAR, 1)
            RepeatRule.NONE -> Unit
        }
        return cal.timeInMillis
    }

    /**
     * The next due date after [base] — always at least one cadence on from it, and never in the past.
     *
     * Both halves of that sentence are load-bearing, and getting either wrong produces a bug that is
     * silent and infuriating:
     *
     * **It always advances at least once.** Tick off "water the plants, due Friday" on a Wednesday
     * and the next occurrence must be *next* Friday. A rule of "the first instant after now" would
     * hand back this Friday again — the same date you just completed — so the task would reappear
     * instantly as an apparent duplicate and the cycle would never actually move on.
     *
     * **It then keeps advancing until it's genuinely in the future.** Leave a daily chore unticked
     * for a fortnight and a single step lands it thirteen days in the past: born overdue, reminder
     * unable to fire (an alarm for a moment that has gone is never scheduled), and quietly useless.
     * The loop walks it forward until it's a date that can actually happen.
     *
     * The guard caps that walk. Reaching it is impossible in practice — 4000 daily steps is eleven
     * years — but an unbounded `while` driven by a stored value is not something to leave lying
     * around.
     *
     * Returns null when the rule is [RepeatRule.NONE].
     */
    fun nextOccurrence(base: Long?, rule: RepeatRule, now: Long = System.currentTimeMillis()): Long? {
        if (rule == RepeatRule.NONE) return null
        var t = advance(base ?: now, rule)
        var guard = 0
        while (t <= now && guard < 4000) {
            t = advance(t, rule)
            guard++
        }
        return t
    }

    /**
     * Build the next occurrence of a just-completed recurring [task], or null if it doesn't repeat
     * or has no due date to advance from (recurrence needs a base instant to step from, which is
     * why the Repeat picker only appears once a due date is set).
     *
     * Pass the task as it was *before* completion — the point is to advance its own due date.
     *
     * The new row is a fresh, pending task with id 0 (Room autogenerates a real one on insert).
     * Attachments are deliberately dropped rather than copied: both the finished original and the
     * new occurrence would otherwise reference the very same on-disk file ids, so permanently
     * deleting the completed original later — from the history page or from Trash — would delete a
     * file the still-active new occurrence was pointing at. Subtasks carry over with every item
     * reset to not-done, so a recurring task's steps don't have to be retyped each cycle.
     */
    fun nextOccurrence(task: Task): Task? {
        val rule = RepeatRule.fromKey(task.repeatRule)
        if (rule == RepeatRule.NONE) return null
        val base = task.dueAt ?: return null
        val nextDue = nextOccurrence(base, rule) ?: return null
        return task.copy(
            id = 0,
            isDone = false,
            createdAt = System.currentTimeMillis(),
            attachments = "[]",
            dueAt = nextDue,
            completedAt = null,
            subtasks = Checklist.resetDone(task.subtasks),
            trashedAt = null
        )
    }
}
