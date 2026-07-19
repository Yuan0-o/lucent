package com.lucent.app.data

/**
 * How important a task is. Stored on [Task.priority] as [value] — a plain Int column, so sorting by
 * priority is a cheap numeric comparison and an old row (0) reads as [NONE] with no backfill.
 *
 * [key] is the stable text form the assistant's tools accept and report; [label] is what the UI
 * shows. [fromKey] is deliberately lenient: a model may say "urgent" or "hi" when it means high,
 * and quietly landing on the right level beats failing the tool call over a synonym.
 */
enum class TaskPriority(val value: Int, val key: String, val label: String) {
    NONE(0, "none", "None"),
    LOW(1, "low", "Low"),
    MEDIUM(2, "medium", "Medium"),
    HIGH(3, "high", "High");

    companion object {
        fun fromValue(value: Int): TaskPriority = entries.firstOrNull { it.value == value } ?: NONE

        fun fromKey(key: String?): TaskPriority {
            val k = key?.trim()?.lowercase() ?: return NONE
            entries.firstOrNull { it.key == k }?.let { return it }
            return when (k) {
                "urgent", "highest", "critical", "hi", "h", "3" -> HIGH
                "med", "normal", "m", "2" -> MEDIUM
                "lo", "l", "1" -> LOW
                else -> NONE
            }
        }
    }
}
