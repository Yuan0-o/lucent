package com.lucent.app.data

import java.time.Instant
import java.time.ZoneId

/**
 * Pure, side-effect-free summaries over a list of tasks.
 *
 * Kept deliberately free of Android, Room, and Compose so it is trivially unit-testable and can be
 * reused anywhere — a screen header, a home-screen widget later, or the assistant answering "what's
 * overdue?" — without any of them re-deriving the same date arithmetic slightly differently (and
 * slightly wrongly). Every function takes the tasks and, where "today" matters, an explicit clock so
 * tests are deterministic and time-zone behaviour is defined rather than incidental.
 *
 * "Due" always refers to a task that is **not done and not trashed** and has a `dueAt`. A completed
 * or trashed task is never counted as overdue or due-soon — finishing something is exactly how you
 * stop it nagging.
 */
object TaskInsights {

    /** A compact snapshot of a task list, suitable for a one-line summary. */
    data class Summary(
        val active: Int,
        val overdue: Int,
        val dueToday: Int,
        val dueThisWeek: Int,
        val completed: Int,
        val withReminders: Int
    ) {
        /** Total tasks that have a live claim on the user's attention right now. */
        val needsAttention: Int get() = overdue + dueToday

        /** Whether there is anything at all worth surfacing in a summary line. */
        val isEmpty: Boolean get() = active == 0 && completed == 0
    }

    /**
     * Build a [Summary] from [tasks]. [now] and [zone] default to the real clock and the device zone;
     * tests pass fixed values. Trashed tasks are ignored entirely — they belong to the Trash screen,
     * not to any count of what's outstanding.
     */
    fun summarize(
        tasks: List<Task>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Summary {
        val live = tasks.filter { it.trashedAt == null }
        val today = now.atZone(zone).toLocalDate()
        val nowMs = now.toEpochMilli()

        var active = 0
        var overdue = 0
        var dueToday = 0
        var dueThisWeek = 0
        var completed = 0
        var withReminders = 0

        for (task in live) {
            if (task.isDone) {
                completed++
                continue
            }
            active++
            if (task.reminderEnabled && task.dueAt != null) withReminders++
            val due = task.dueAt ?: continue
            when {
                due < nowMs -> overdue++
                else -> {
                    val dueDate = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
                    if (dueDate == today) dueToday++
                    // "This week" = the next 7 days inclusive of today, a rolling window rather than a
                    // calendar week, which is what "due this week" means when you're looking at a list.
                    if (!dueDate.isBefore(today) && dueDate.isBefore(today.plusDays(7))) dueThisWeek++
                }
            }
        }

        return Summary(
            active = active,
            overdue = overdue,
            dueToday = dueToday,
            dueThisWeek = dueThisWeek,
            completed = completed,
            withReminders = withReminders
        )
    }

    /**
     * A short, human one-liner for a summary — "3 overdue · 2 due today", or "All clear" when nothing
     * is pressing. Returns null when there are no active tasks at all, so a caller can simply hide the
     * line rather than show something empty. Ordered by urgency so the most important figure leads.
     */
    fun headline(summary: Summary): String? {
        if (summary.active == 0) return null
        val parts = buildList {
            if (summary.overdue > 0) add("${summary.overdue} overdue")
            if (summary.dueToday > 0) add("${summary.dueToday} due today")
            if (summary.overdue == 0 && summary.dueToday == 0 && summary.dueThisWeek > 0) {
                add("${summary.dueThisWeek} due this week")
            }
        }
        return if (parts.isEmpty()) "All clear" else parts.joinToString(" · ")
    }
}
