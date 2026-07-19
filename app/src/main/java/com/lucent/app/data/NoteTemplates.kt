package com.lucent.app.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Which glyph a template's chip wears.
 *
 * An enum rather than a Compose `ImageVector`, so this file stays in `data` and free of UI types —
 * the mapping to an actual icon lives in `ui/NoteColors.kt`'s neighbourhood, where Compose belongs.
 * A chip with a picture on it is scannable at a glance; four identical text chips have to be read.
 */
enum class TemplateIcon { JOURNAL, MEETING, IDEA, CHECKLIST }

/**
 * One-tap starters for a new note.
 *
 * A blank page is the most expensive thing a note app can show someone, and the fix is not a
 * template *system* — no user-defined templates, no variables, no template manager to maintain.
 * It's four sensible shapes, offered only on the new-note screen (never when editing, where they'd
 * be a footgun that overwrites what you already typed), each of which just fills the composer's
 * fields with text the user is then free to ignore, edit, or delete.
 *
 * Templates write Markdown headings and bullets because the note detail page renders Markdown, so a
 * template produces something that reads as a formatted document rather than a page of literal
 * hashes. The checklist template goes further and returns actual checklist items, since "a list of
 * things to tick off" is a mode the note model already has and faking it with text would be worse.
 */
enum class NoteTemplate(val label: String, val iconName: TemplateIcon) {

    JOURNAL("Journal entry", TemplateIcon.JOURNAL),
    MEETING("Meeting notes", TemplateIcon.MEETING),
    IDEA("Project idea", TemplateIcon.IDEA),
    CHECKLIST("Checklist", TemplateIcon.CHECKLIST);

    /** The composer state this template produces. */
    fun prefill(): Prefill {
        val today = LocalDate.now()
        val longDate = today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
        val shortDate = today.format(DateTimeFormatter.ofPattern("d MMM yyyy"))

        return when (this) {
            JOURNAL -> Prefill(
                title = longDate,
                body = buildString {
                    appendLine("## How today went")
                    appendLine()
                    appendLine()
                    appendLine("## What I'm grateful for")
                    appendLine()
                    appendLine()
                    appendLine("## Tomorrow")
                    appendLine()
                }
            )

            MEETING -> Prefill(
                title = "Meeting — $shortDate",
                tags = setOf("Work"),
                body = buildString {
                    appendLine("**Attendees:** ")
                    appendLine("**Date:** $shortDate")
                    appendLine()
                    appendLine("## Discussion")
                    appendLine("- ")
                    appendLine()
                    appendLine("## Decisions")
                    appendLine("- ")
                    appendLine()
                    appendLine("## Action items")
                    appendLine("- ")
                }
            )

            IDEA -> Prefill(
                title = "",
                body = buildString {
                    appendLine("## The idea")
                    appendLine()
                    appendLine()
                    appendLine("## Why it's worth doing")
                    appendLine()
                    appendLine()
                    appendLine("## First step")
                    appendLine()
                    appendLine()
                    appendLine("## Open questions")
                    appendLine("- ")
                }
            )

            CHECKLIST -> Prefill(
                title = "",
                isChecklist = true,
                checklist = listOf(Checklist.newItem(""))
            )
        }
    }

    /**
     * What a template hands back to the composer. Every field is optional and defaults to "leave
     * the composer alone", so adding a template later never means touching the composer.
     */
    data class Prefill(
        val title: String = "",
        val body: String = "",
        val tags: Set<String> = emptySet(),
        val isChecklist: Boolean = false,
        val checklist: List<ChecklistItem> = emptyList()
    )
}
