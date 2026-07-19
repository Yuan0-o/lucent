package com.lucent.app.data

/**
 * The one place that decides when a note's previous text is worth keeping.
 *
 * Every path that overwrites a note — the editor, the assistant's `update_note` tool, a restore
 * from an older version — routes through [recordIfChanged] first, so history is captured
 * identically no matter who did the writing. Keeping that decision here (rather than duplicating a
 * `noteVersionDao().insert(...)` at each call site) is what stops the two paths from drifting apart
 * and quietly leaving one of them without a safety net.
 *
 * Everything stays on the device. A version is just another row in the same local database as the
 * note it belongs to; nothing about this feature talks to a network.
 */
object NoteHistory {

    /**
     * How many revisions of a single note are kept. Twenty covers "I've been editing this all
     * week and want the version from Tuesday" while keeping the table small — text-only rows, so
     * even twenty revisions of a long note is a handful of kilobytes. Older revisions fall off the
     * end automatically (see [NoteVersionDao.trimTo]).
     */
    const val MAX_VERSIONS_PER_NOTE = 20

    /**
     * Snapshot [existing] as it stands right now, but only if the incoming text actually differs
     * from it.
     *
     * The guard matters more than it looks. Without it, opening a note and tapping Save without
     * typing anything would push an identical revision onto the stack, and a few idle saves would
     * evict the genuinely-different version the user is one day going to want back. Attachments,
     * pin, colour, and archive state are all excluded from the comparison on purpose: they are not
     * part of what a version stores, so a change to one of them is not a text edit and should not
     * consume a history slot.
     *
     * Call this *before* writing the update, while [existing] is still the live row.
     */
    suspend fun recordIfChanged(
        db: AppDatabase,
        existing: Note,
        newTitle: String,
        newBody: String,
        newTags: String,
        newIsChecklist: Boolean,
        newChecklist: String
    ) {
        val unchanged = existing.title == newTitle &&
            existing.body == newBody &&
            existing.tags == newTags &&
            existing.isChecklist == newIsChecklist &&
            existing.checklist == newChecklist
        if (unchanged) return

        db.noteVersionDao().insert(
            NoteVersion(
                noteId = existing.id,
                title = existing.title,
                body = existing.body,
                tags = existing.tags,
                isChecklist = existing.isChecklist,
                checklist = existing.checklist,
                // The note's own updatedAt, not "now": this row records what the note said during
                // the period ending at this instant, so that's the timestamp people recognise.
                savedAt = existing.updatedAt
            )
        )
        db.noteVersionDao().trimTo(existing.id, MAX_VERSIONS_PER_NOTE)
    }

    /**
     * Apply [version] back onto [note], returning the note as it should be written.
     *
     * Restoring is itself an edit, so the caller records the current text as a fresh version first
     * (via [recordIfChanged]) — which is what makes "restore" undoable in turn, rather than a
     * one-way trip that destroys whatever you had before you restored.
     *
     * Only the fields a version actually stores are touched. Attachments, pin, colour, and archive
     * state are left exactly as they are on the live note: restoring last Tuesday's wording should
     * not also un-archive the note or resurrect a file that was deliberately removed since.
     */
    fun applyTo(note: Note, version: NoteVersion): Note = note.copy(
        title = version.title,
        body = version.body,
        tags = version.tags,
        isChecklist = version.isChecklist,
        checklist = version.checklist,
        updatedAt = System.currentTimeMillis()
    )

    /** Drop a note's entire history. Called wherever a note is *permanently* deleted. */
    suspend fun deleteAllFor(db: AppDatabase, noteId: Long) {
        db.noteVersionDao().deleteForNote(noteId)
    }
}
