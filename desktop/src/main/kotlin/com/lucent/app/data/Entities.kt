package com.lucent.app.data

// Desktop twin of app/src/main/java/com/lucent/app/data/Entities.kt.
//
// Field-for-field identical to the Android Room entities (same names, same defaults, same
// nullability) so every piece of shared logic — the assistant tools, search, insights, and above
// all BackupManager — compiles and behaves identically. Only the Room annotations are gone: the
// desktop build persists these through the hand-rolled SQLite layer in Db.kt/Daos.kt, using the
// same table and column names Room generates on Android, which keeps the mental model (and any
// future schema tooling) one-to-one.

data class Note(
    val id: Long = 0,
    val title: String,
    val body: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val tags: String = "",
    // JSON array of attachments: [{"mime":..,"data":<AttachmentStore id>,"name":..}, ...]
    val attachments: String = "[]",
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    val pinned: Boolean = false,
    // A NoteColor key ("" = default/no tint) — see ui/NoteColors.kt.
    val color: String = "",
    val isChecklist: Boolean = false,
    val checklist: String = "[]",
    // Soft-delete: null = not in the trash. See TrashCleanup.
    val trashedAt: Long? = null
)

data class Task(
    val id: Long = 0,
    val title: String,
    val isDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val attachments: String = "[]",
    val dueAt: Long? = null,
    val notes: String = "",
    val completedAt: Long? = null,
    // A TaskPriority.value (0 none, 1 low, 2 medium, 3 high) — see data/TaskPriority.kt.
    val priority: Int = 0,
    val pinned: Boolean = false,
    // JSON array of subtask checklist items — see Checklist.kt.
    val subtasks: String = "[]",
    // A RepeatRule.key (see data/Recurrence.kt). Only meaningful when dueAt is set.
    val repeatRule: String = "NONE",
    val reminderEnabled: Boolean = false,
    val trashedAt: Long? = null
)

/** One historical revision of a note's text — see the Android original for the full rationale. */
data class NoteVersion(
    val id: Long = 0,
    val noteId: Long,
    val title: String,
    val body: String,
    val tags: String = "",
    val isChecklist: Boolean = false,
    val checklist: String = "[]",
    val savedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentMime: String? = null,
    val attachmentData: String? = null,
    val attachmentName: String? = null,
    val conversationId: Long = 1,
    // Approximate tokens this turn cost — see data/TokenEstimator.kt.
    val tokens: Int = 0
)

data class ChatConversation(
    val id: Long = 0,
    val title: String = "New conversation",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
