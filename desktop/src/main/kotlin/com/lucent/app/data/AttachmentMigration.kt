package com.lucent.app.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.first

/**
 * One-shot migration from the old "Base64 payload inside the database row" format to the new
 * disk-backed [AttachmentStore] form.
 *
 * ### Why an app-level migration and not a Room [Migration]
 * Room migrations are raw SQL that runs while the database opens. This one has to (a) decode a
 * potentially very large Base64 blob, (b) write bytes out to the file system, (c) rewrite the
 * row with the new id — Kotlin plus disk I/O. Doing that from a schema migration would tie up
 * the database open call for a very long time on a big library. Instead we let Room open the
 * DB fast on the old schema-compatible column, and then walk the rows in the background from
 * an ordinary coroutine.
 *
 * ### Idempotency
 * The migration decides row-by-row whether it still has work to do: for each attachment, if
 * [Attachment.data] already looks like an [AttachmentStore] id it's already been converted
 * and is skipped. That means a mid-migration crash (or a user who force-quits the app) simply
 * picks up where it left off next launch, and it's safe to run repeatedly.
 *
 * ### Orphan sweep
 * After the migration pass, and after every backup import, we prune on-disk files that no row
 * currently references — the residue of composers cancelled after picking a file, attachments
 * replaced by same-name uploads, etc.
 */
object AttachmentMigration {

    /**
     * Run the migration if it hasn't finished before. Cheap no-op after the completion flag is
     * set, so it's safe to call from every app startup.
     */
    suspend fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext)
        if (settings.attachmentsMigrated.first()) {
            // Migration already ran to completion; still worth pruning any orphans left over
            // by day-to-day use (cancelled composers, replaced files, imported backups).
            pruneOrphans(appContext)
            return
        }

        val db = AppDatabase.getInstance(appContext)
        // getAllOnce(), NOT getAll().first().
        //
        // getAll() is the *home list* query: it excludes archived and trashed notes. Reading the
        // migration's world through it meant an archived note's attachments were never migrated —
        // and, far worse, that pruneOrphans() below would compute its "referenced" set without them
        // and delete the files of every archived note as orphans. A silent, permanent data loss
        // triggered by the entirely reasonable act of archiving something.
        //
        // Anything that maintains an invariant across the whole table has to see the whole table.
        val notes = db.noteDao().getAllOnce()
        val tasks = db.taskDao().getAllOnce()

        var anyRemaining = false
        notes.forEach { note ->
            val (newJson, remaining) = migrateAttachmentsJson(appContext, note.attachments)
            if (newJson != note.attachments) {
                db.noteDao().update(note.copy(attachments = newJson))
            }
            if (remaining) anyRemaining = true
        }
        tasks.forEach { task ->
            val (newJson, remaining) = migrateAttachmentsJson(appContext, task.attachments)
            if (newJson != task.attachments) {
                db.taskDao().update(task.copy(attachments = newJson))
            }
            if (remaining) anyRemaining = true
        }

        pruneOrphans(appContext)

        // Only latch the "done" flag if we know we handled every legacy blob we saw. If any
        // slipped past (e.g. couldn't be decoded), the migration will try them again next
        // launch instead of quietly leaving them as un-migrated Base64 forever.
        if (!anyRemaining) settings.setAttachmentsMigrated(true)
    }

    /**
     * Convert every legacy Base64 attachment in [attachmentsJson] to its disk-backed form.
     * Returns the new JSON (unchanged if there was nothing to migrate) and a "remaining"
     * flag: true if any attachment couldn't be migrated (bad payload, disk write failed).
     */
    private fun migrateAttachmentsJson(
        context: Context,
        attachmentsJson: String
    ): Pair<String, Boolean> {
        val list = Attachments.parse(attachmentsJson)
        if (list.isEmpty()) return attachmentsJson to false

        var changed = false
        var remaining = false
        val migrated = list.map { att ->
            if (AttachmentStore.looksLikeId(att.data)) return@map att
            val bytes = try {
                Base64.decode(att.data, Base64.DEFAULT)
            } catch (t: Throwable) {
                remaining = true
                return@map att
            }
            val id = AttachmentStore.importBytes(context, bytes)
            if (id == null) {
                remaining = true
                att
            } else {
                changed = true
                att.copy(data = id)
            }
        }
        return (if (changed) Attachments.serialize(migrated) else attachmentsJson) to remaining
    }

    /**
     * Encrypt any attachment still sitting on disk in the clear.
     *
     * This is what turns "new attachments are encrypted" into "*all* attachments are encrypted",
     * without ever needing a big-bang conversion the user has to survive. Each file is converted
     * independently and atomically (see [AttachmentStore.encryptExistingFile]); a failure on one
     * leaves that file readable and simply gets retried next launch, rather than taking the whole
     * library down with it. Files already encrypted are skipped, so this is free once it has caught
     * up — which is why it can just run on every start.
     */
    suspend fun encryptExistingAttachments(context: Context) {
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        val referenced = buildSet {
            db.noteDao().getAllOnce().forEach { addAll(Attachments.idsFromJson(it.attachments)) }
            db.taskDao().getAllOnce().forEach { addAll(Attachments.idsFromJson(it.attachments)) }
        }
        referenced.forEach { id ->
            try {
                AttachmentStore.encryptExistingFile(appContext, id)
            } catch (t: Throwable) {
                // One stubborn file must not stop the rest. It stays plaintext and gets another go.
            }
        }
    }

    /** Sweep disk files that no note or task currently references. */
    suspend fun pruneOrphans(context: Context) {
        val db = AppDatabase.getInstance(context)
        // Unfiltered, for the reason spelled out in runIfNeeded above: an archived or trashed note's
        // attachments are still referenced and must never be swept as orphans.
        val notes = db.noteDao().getAllOnce()
        val tasks = db.taskDao().getAllOnce()
        val referenced = buildSet {
            notes.forEach { addAll(Attachments.idsFromJson(it.attachments)) }
            tasks.forEach { addAll(Attachments.idsFromJson(it.attachments)) }
        }
        AttachmentStore.pruneOrphans(context, referenced)
    }
}
