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
 *
 * Localization: labels, scaffold text, and date formats all come from the catalog (the localization
 * task). `label` is a computed property rather than a constructor value so a chip re-renders in the
 * new language the moment the setting changes; the scaffold a template *inserts* is generated in
 * the language active at that moment and then belongs to the user like any other typed text — it is
 * deliberately NOT retranslated later, because at that point it's their document, not our UI.
 */
enum class NoteTemplate(val iconName: TemplateIcon) {

    JOURNAL(TemplateIcon.JOURNAL),
    MEETING(TemplateIcon.MEETING),
    IDEA(TemplateIcon.IDEA),
    CHECKLIST(TemplateIcon.CHECKLIST);

    /** The chip's visible name, in the active UI language. */
    val label: String
        get() = when (this) {
            JOURNAL -> com.lucent.app.i18n.S.tplJournal
            MEETING -> com.lucent.app.i18n.S.tplMeeting
            IDEA -> com.lucent.app.i18n.S.tplIdea
            CHECKLIST -> com.lucent.app.i18n.S.tplChecklist
        }

    /** The composer state this template produces. */
    fun prefill(): Prefill {
        val today = LocalDate.now()
        // Date *patterns* are catalog entries too, because a localized date is more than localized
        // month names: Chinese wants year-month-day with its own separators, not an English
        // ordering rendered with a Chinese locale. The locale supplies the day and month words;
        // the pattern supplies the shape.
        val locale = com.lucent.app.i18n.lucentLocale()
        val longDate = today.format(DateTimeFormatter.ofPattern(com.lucent.app.i18n.S.tplLongDatePattern, locale))
        val shortDate = today.format(DateTimeFormatter.ofPattern(com.lucent.app.i18n.S.tplShortDatePattern, locale))

        return when (this) {
            JOURNAL -> Prefill(
                title = longDate,
                body = com.lucent.app.i18n.S.tplJournalBody
            )

            MEETING -> Prefill(
                title = com.lucent.app.i18n.S.tplMeetingTitle(shortDate),
                tags = setOf(com.lucent.app.i18n.S.tagWork),
                body = com.lucent.app.i18n.S.tplMeetingBody(shortDate)
            )

            IDEA -> Prefill(
                title = "",
                body = com.lucent.app.i18n.S.tplIdeaBody
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
