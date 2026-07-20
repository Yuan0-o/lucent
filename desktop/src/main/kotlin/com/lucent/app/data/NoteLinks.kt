package com.lucent.app.data

/**
 * Wiki-style links between notes: write `[[Groceries]]` anywhere in a note's body and it becomes a
 * tappable link to the note titled "Groceries", while that note grows a "Linked from" list showing
 * everything that points at it.
 *
 * This is the one piece of knowledge-management machinery worth having early, and it is the whole
 * of it. A backlink index makes a pile of notes navigable in a way that tags and search cannot:
 * search answers "where did I write this word", tags answer "what kind of thing is this", but a
 * link answers "what is this *about*, and what else cares about it" — and the backlink is the half
 * of that question the writer never has to remember to answer.
 *
 * ### Why links are derived, not stored
 *
 * There is no `links` table and no `[[...]]` index column. The links of a note are simply *read out
 * of its text* whenever they're needed, and backlinks are computed by scanning the notes list the
 * screen already has in memory.
 *
 * That is a deliberate trade. A stored index would have to be kept in step with every edit from
 * every direction — the editor, the assistant's `update_note`, a version restore, an import — and
 * every one of those is a chance for the index to drift out of sync with the text, at which point
 * the graph starts lying to the user. Deriving it means the text *is* the index: it cannot be
 * stale, it cannot be corrupted, it needs no migration, and it survives a backup round-trip for
 * free because the links travel inside the body that was already being backed up. The cost is a
 * scan over notes already loaded, which is nothing at the scale a phone holds.
 *
 * ### Matching
 *
 * A link resolves to a note by title, case-insensitively, trimmed. Titles are what people actually
 * type, and an id would make the syntax unwriteable by hand. Retitling a note therefore breaks
 * links into it — the same behaviour as every other tool that links by title — so a broken link is
 * rendered visibly as broken rather than silently doing nothing, and tapping it offers to create
 * the missing note.
 */
object NoteLinks {

    /**
     * `[[Some title]]`. Non-greedy, and it refuses to swallow `]` so a stray bracket in prose can't
     * make a link run away to the end of the paragraph.
     */
    private val LINK = Regex("""\[\[([^\[\]]+)]]""")

    /** The raw link targets written inside [text], in order, duplicates included. */
    fun linkTargets(text: String): List<String> =
        LINK.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    /**
     * The link targets of a note. A checklist note's items are searched too, so `[[Recipes]]`
     * inside a checklist row links exactly like one in a body would.
     */
    fun linkTargets(note: Note): List<String> {
        val text = if (note.isChecklist) {
            Checklist.parse(note.checklist).joinToString("\n") { it.text }
        } else {
            note.body
        }
        return linkTargets(text).distinct()
    }

    /** Resolve one `[[target]]` to a live note, by case-insensitive title. Null when it's broken. */
    fun resolve(target: String, notes: List<Note>): Note? {
        val wanted = target.trim()
        if (wanted.isEmpty()) return null
        notes.firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) }?.let { return it }
        // Fall back to a single unambiguous partial match, the same forgiving rule the assistant
        // uses for titles. Two candidates means we genuinely don't know, so the link stays broken
        // rather than guessing and quietly opening the wrong note.
        val partial = notes.filter { it.title.contains(wanted, ignoreCase = true) }
        return if (partial.size == 1) partial.first() else null
    }

    /** The notes [note] points *at*, in writing order, with broken links dropped. */
    fun outgoing(note: Note, notes: List<Note>): List<Note> =
        linkTargets(note).mapNotNull { resolve(it, notes) }.filter { it.id != note.id }.distinctBy { it.id }

    /**
     * The notes that point *at* [note] — its backlinks.
     *
     * Trashed notes are excluded by construction, because [notes] is only ever the live list the
     * screen is already showing. A note in the bin shouldn't keep asserting a relationship the user
     * has, as far as they're concerned, deleted.
     */
    fun backlinks(note: Note, notes: List<Note>): List<Note> = notes.filter { candidate ->
        candidate.id != note.id && linkTargets(candidate).any { target ->
            resolve(target, notes)?.id == note.id
        }
    }

    /** Every `[[target]]` in [note] that resolves to nothing — offered as "create this note". */
    fun brokenLinks(note: Note, notes: List<Note>): List<String> =
        linkTargets(note).filter { resolve(it, notes) == null }
}
