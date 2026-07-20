package com.lucent.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exports every note as one plain Markdown document.
 *
 * The `.json` backup already round-trips perfectly *back into Lucent*, and that's the right format
 * for restoring. This is the other half of owning your data: a file that is still readable in ten
 * years by something that has never heard of Lucent. A backup you can only open with the app that
 * wrote it is a hostage, not a backup — and for an app whose whole pitch is that your notes live on
 * your device and answer to you, being able to walk away with them in a format every editor on
 * earth can read is not a nice-to-have, it's the point.
 *
 * So: no app-specific wrapper, no base64, no schema. Headings, tags, checkboxes, and a horizontal
 * rule between notes. Attachments are named but not embedded — a Markdown file cannot carry bytes,
 * and silently dropping them would be dishonest, so each note lists the files it had and the export
 * says plainly where to get them (the JSON backup, which does carry them).
 */
object MarkdownExport {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(stamp)

    /**
     * Render [notes] as a single Markdown document.
     *
     * Trashed notes are left out — they're deleted, as far as the user is concerned, and an export
     * that quietly resurrects them in a file they're about to email themselves would be a nasty
     * surprise. Archived notes *are* included, and labelled, because archiving means "put this
     * away", not "throw it out".
     */
    fun render(notes: List<Note>): String {
        val live = notes.filter { it.trashedAt == null }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })

        val sb = StringBuilder()
        sb.appendLine("# Lucent notes")
        sb.appendLine()
        sb.appendLine("_${live.size} note${if (live.size == 1) "" else "s"}, exported ${formatTime(System.currentTimeMillis())}._")
        sb.appendLine()
        sb.appendLine("Attachments are listed by name but not embedded — Markdown can't carry files.")
        sb.appendLine("Use the `.json` backup if you need the attachment bytes as well.")
        sb.appendLine()

        if (live.isEmpty()) {
            sb.appendLine("---")
            sb.appendLine()
            sb.appendLine("_No notes yet._")
            return sb.toString()
        }

        live.forEach { note ->
            sb.appendLine("---")
            sb.appendLine()
            sb.appendLine("## ${note.title.ifBlank { "Untitled" }}")
            sb.appendLine()

            val meta = buildList {
                add("Updated ${formatTime(note.updatedAt)}")
                if (note.pinned) add("Pinned")
                if (note.archived) add("Archived")
                val tags = note.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (tags.isNotEmpty()) add(tags.joinToString(" ") { "#$it" })
            }
            sb.appendLine("_${meta.joinToString(" · ")}_")
            sb.appendLine()

            if (note.isChecklist) {
                val items = Checklist.parse(note.checklist)
                if (items.isEmpty()) {
                    sb.appendLine("_(empty checklist)_")
                } else {
                    sb.appendLine(Checklist.toMarkdown(note.checklist))
                }
                sb.appendLine()
            } else if (note.body.isNotBlank()) {
                // The body is already Markdown as far as the app is concerned (the detail page
                // renders it as such), so it goes out verbatim rather than being escaped — escaping
                // it would turn every heading the user wrote into a literal '#'.
                sb.appendLine(note.body.trimEnd())
                sb.appendLine()
            }

            val attachments = Attachments.parse(note.attachments)
            if (attachments.isNotEmpty()) {
                sb.appendLine("**Attachments:** ${attachments.joinToString(", ") { it.name }}")
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    /**
     * Render [tasks] as a single Markdown document — the task-side equivalent of [render] for notes,
     * added so tasks can be exported to a portable, Lucent-independent file too (previously only
     * notes could). Trashed tasks are excluded for the same reason as notes; completed tasks are kept
     * and marked done with a `[x]` checkbox so the file is a faithful record. Each task carries its
     * created/due/priority/repeat metadata, its notes, its subtasks as a checklist, and its
     * attachment names.
     */
    fun renderTasks(tasks: List<Task>): String {
        val live = tasks.filter { it.trashedAt == null }
            .sortedWith(
                compareByDescending<Task> { it.pinned }
                    .thenBy { it.isDone }
                    .thenByDescending { it.createdAt }
            )

        val sb = StringBuilder()
        sb.appendLine("# Lucent tasks")
        sb.appendLine()
        sb.appendLine("_${live.size} task${if (live.size == 1) "" else "s"}, exported ${formatTime(System.currentTimeMillis())}._")
        sb.appendLine()
        sb.appendLine("Attachments are listed by name but not embedded — Markdown can't carry files.")
        sb.appendLine("Use the `.json` backup if you need the attachment bytes as well.")
        sb.appendLine()

        if (live.isEmpty()) {
            sb.appendLine("---")
            sb.appendLine()
            sb.appendLine("_No tasks yet._")
            return sb.toString()
        }

        live.forEach { task ->
            sb.appendLine("---")
            sb.appendLine()
            val box = if (task.isDone) "[x]" else "[ ]"
            sb.appendLine("## $box ${task.title.ifBlank { "Untitled task" }}")
            sb.appendLine()

            val meta = buildList {
                add("Created ${formatTime(task.createdAt)}")
                task.dueAt?.let { add("Due ${formatTime(it)}") }
                if (task.pinned) add("Pinned")
                TaskPriority.fromValue(task.priority).takeIf { it != TaskPriority.NONE }?.let { add("Priority: ${it.label}") }
                RepeatRule.fromKey(task.repeatRule).takeIf { it != RepeatRule.NONE }?.let { add("Repeats: ${it.label}") }
                if (task.isDone) add("Done")
            }
            sb.appendLine("_${meta.joinToString(" · ")}_")
            sb.appendLine()

            if (task.notes.isNotBlank()) {
                sb.appendLine(task.notes.trimEnd())
                sb.appendLine()
            }

            val subtasks = Checklist.parse(task.subtasks)
            if (subtasks.isNotEmpty()) {
                sb.appendLine("**Subtasks:**")
                sb.appendLine(Checklist.toMarkdown(task.subtasks))
                sb.appendLine()
            }

            val attachments = Attachments.parse(task.attachments)
            if (attachments.isNotEmpty()) {
                sb.appendLine("**Attachments:** ${attachments.joinToString(", ") { it.name }}")
                sb.appendLine()
            }
        }

        return sb.toString()
    }
}
