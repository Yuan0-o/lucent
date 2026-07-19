package com.lucent.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    // Indices for the list queries (settings task 8). The home grid filters on archived + trashedAt
    // and sorts by updatedAt; the archive and trash screens filter on archived / trashedAt. Indexing
    // those columns lets SQLite satisfy the WHERE/ORDER BY without scanning every row. Names follow
    // Room's own convention (index_<table>_<column>) so a migrated DB and a freshly created one
    // match and schema validation passes — see MIGRATION_10_11, which creates these by those names.
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["archived"]),
        Index(value = ["trashedAt"])
    ]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val tags: String = "",
    // JSON array of attachments: [{"mime":..,"data":<id>,"name":..}, ...]
    // With disk-backed storage, "data" is now an AttachmentStore id (a UUID string) instead
    // of a Base64 payload. Legacy rows still hold Base64 until the startup migration rewrites
    // them; see AttachmentMigration.
    val attachments: String = "[]",
    // Archiving: an archived note is hidden from the Notes home page and only shown on the
    // dedicated archive screen. archivedAt records when it was archived so the archive can sort
    // by time; it is null for notes that have never been archived.
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    // Pinned notes float to the top of the home list regardless of the chosen sort order.
    val pinned: Boolean = false,
    // A NoteColor key ("" = default/no tint, otherwise "red"/"orange"/... — see ui/NoteColors.kt).
    // Rendered as a tinted frosted-glass card so colour-coding still reads as glass, not a flat chip.
    val color: String = "",
    // JSON array of checklist items: [{"id":..,"text":..,"done":..}, ...] — see Checklist.kt.
    // Only meaningful when isChecklist is true; kept even when false so switching a note back and
    // forth between plain-text and checklist mode never throws away either version of its content.
    val isChecklist: Boolean = false,
    val checklist: String = "[]",
    // Soft-delete: a trashed note is hidden from the home list, the archive, and search, and is
    // shown only on the Trash screen, until it's restored or TrashCleanup permanently removes it
    // after TrashCleanup.RETENTION_DAYS days. Null = not in the trash.
    val trashedAt: Long? = null
)

@Entity(
    tableName = "tasks",
    // Indices for the list queries (settings task 8): the active list filters isDone + trashedAt
    // and sorts by createdAt; completed and trash filter on isDone / trashedAt. Same Room naming
    // convention as above so MIGRATION_10_11 creates matching indices and validation passes.
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["isDone"]),
        Index(value = ["trashedAt"])
    ]
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val isDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // JSON array of attachments: [{"mime":..,"data":<id>,"name":..}, ...]
    // With disk-backed storage, "data" is now an AttachmentStore id (a UUID string) instead
    // of a Base64 payload. Legacy rows still hold Base64 until the startup migration rewrites
    // them; see AttachmentMigration.
    val attachments: String = "[]",
    // Optional user-set estimated completion time. Null = no due date set.
    val dueAt: Long? = null,
    // Optional free-text description / remarks for the task.
    val notes: String = "",
    // The moment the user marked this task complete. Null while it's still pending. The
    // completed-tasks history page sorts by this so newly-finished tasks land at the top;
    // the home list shows only rows where isDone = 0, so completed tasks disappear from it
    // automatically the instant they're checked off.
    val completedAt: Long? = null,
    // A TaskPriority.value (0 none, 1 low, 2 medium, 3 high) — see data/TaskPriority.kt. Stored as
    // a plain Int so it sorts naturally, and so old rows (which default to 0) read as NONE.
    val priority: Int = 0,
    // Pinned tasks float to the top of the active list regardless of the chosen sort order.
    val pinned: Boolean = false,
    // JSON array of subtask checklist items: [{"id":..,"text":..,"done":..}, ...] — see
    // Checklist.kt. A task's own small to-do list, separate from its free-text notes.
    val subtasks: String = "[]",
    // A RepeatRule.key (see data/Recurrence.kt). Only meaningful when dueAt is set — recurrence
    // needs a base instant to advance from each time the task is completed.
    val repeatRule: String = "NONE",
    // Whether a local notification should fire at dueAt. See reminders/ReminderScheduler.kt.
    val reminderEnabled: Boolean = false,
    // Soft-delete: a trashed task is hidden from the active list, the completed-tasks history, and
    // search, and is shown only on the Trash screen, until it's restored or TrashCleanup
    // permanently removes it. Null = not in the trash.
    val trashedAt: Long? = null
)

/**
 * One historical revision of a note's text, captured immediately *before* an edit overwrites it.
 *
 * This is the local, offline answer to "I just wiped out a paragraph and saved" — the same safety
 * net mature note apps provide, except the history lives entirely on the device next to the note.
 * Nothing is uploaded and nothing leaves the phone.
 *
 * Only text is snapshotted (title/body/tags/checklist), never attachments: an attachment's bytes
 * live once in [AttachmentStore] and are referenced by id, so copying that reference into a
 * version row would let restoring an old version resurrect a file the note had already dropped —
 * or let deleting the note orphan a file a version still pointed at. Text is cheap, safe, and it's
 * what people actually lose.
 *
 * Rows are capped per note by [NoteVersionDao.trimTo] so history can never grow without bound, and
 * they are deleted along with their note wherever a note is permanently removed.
 */
@Entity(
    tableName = "note_versions",
    indices = [Index(value = ["noteId"])]
)
data class NoteVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val body: String,
    val tags: String = "",
    val isChecklist: Boolean = false,
    val checklist: String = "[]",
    // When this revision was the note's live content — i.e. the note's own updatedAt at the moment
    // it was replaced. Shown in the history list, so a version reads as "what the note said on
    // July 3rd", which is the question people actually ask.
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentMime: String? = null,
    val attachmentData: String? = null,
    val attachmentName: String? = null,
    // Which conversation this message belongs to. Lets the user start a new conversation while
    // keeping the old ones (see ChatConversation). Existing pre-sessions rows are migrated to
    // conversation id 1 — the initial conversation — by MIGRATION_7_8.
    val conversationId: Long = 1,
    // Approximate tokens this turn cost, shown as a muted footnote under the reply (issue 9). Only
    // meaningful on assistant messages — it's the estimated input-context + output size for the
    // turn that produced this reply. 0 on user messages and on rows created before the column
    // existed, in which case the footnote is simply hidden. See data/TokenEstimator.kt.
    val tokens: Int = 0
)

/**
 * A single assistant conversation (chat session). "Start new conversation" inserts one of these
 * and points new messages at it; the previous conversations stay in the database and can be
 * reopened from the conversation list. [title] is a short auto-derived label (first user message,
 * trimmed) shown in that list; [updatedAt] is bumped on each new message so the list can sort
 * most-recent-first.
 */
@Entity(tableName = "chat_conversations")
data class ChatConversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "New conversation",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
