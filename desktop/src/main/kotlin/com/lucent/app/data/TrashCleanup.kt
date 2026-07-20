package com.lucent.app.data

import android.content.Context
import com.lucent.app.reminders.ReminderScheduler

/**
 * Auto-empties the Trash.
 *
 * A note or task the user deletes is only *soft*-deleted — [Note.trashedAt] / [Task.trashedAt] gets
 * stamped, but the row (and its attachment files, and a note's revision history) stay on disk so
 * the Trash screen can restore it. This sweep is what actually finishes the job: anything trashed
 * more than [RETENTION_DAYS] days ago is permanently removed — row, on-disk attachments, note
 * history, and any pending reminder — the next time the app starts after that window passes.
 *
 * Mirrors [AttachmentMigration]'s "cheap, idempotent, safe to run on every launch" shape: nothing
 * here depends on being called at a particular moment, only on being called occasionally. Because
 * it runs on-device at launch rather than on a schedule, a phone that sits unused for a year simply
 * purges everything eligible the next time it's opened, which is the behaviour people expect.
 */
object TrashCleanup {

    const val RETENTION_DAYS = 30
    private const val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60 * 60 * 1000

    suspend fun purgeExpired(context: Context) {
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS

        db.noteDao().getAllOnce().forEach { note ->
            val trashedAt = note.trashedAt ?: return@forEach
            if (trashedAt >= cutoff) return@forEach
            purgeNote(appContext, db, note)
        }

        db.taskDao().getAllOnce().forEach { task ->
            val trashedAt = task.trashedAt ?: return@forEach
            if (trashedAt >= cutoff) return@forEach
            purgeTask(appContext, db, task)
        }

        // Safety net: a note whose history somehow outlived it (an interrupted delete, a
        // hand-edited database) leaves rows nothing can reach. Cheap to sweep, so we always do.
        db.noteVersionDao().pruneOrphaned()
    }

    /**
     * Permanently delete one note and everything that belongs only to it: its on-disk attachment
     * files first, then its revision history, then the row.
     *
     * Files go first so a failure halfway can never leave the row gone but the bytes stranded —
     * the worst case is a file deleted while the row survives, which the next orphan sweep and the
     * app's own "missing attachment" handling both cope with gracefully. The other order would leak
     * disk space forever with nothing left pointing at it.
     *
     * Shared by the Trash screen's "Delete forever" / "Empty trash" actions and by the retention
     * sweep above, so a purge means exactly the same thing however it was triggered.
     */
    suspend fun purgeNote(context: Context, db: AppDatabase, note: Note) {
        val appContext = context.applicationContext
        Attachments.parse(note.attachments).forEach { att ->
            if (AttachmentStore.looksLikeId(att.data)) AttachmentStore.delete(appContext, att.data)
        }
        NoteHistory.deleteAllFor(db, note.id)
        db.noteDao().delete(note)
    }

    /** Permanently delete one task: its attachment files, then its alarm, then the row. */
    suspend fun purgeTask(context: Context, db: AppDatabase, task: Task) {
        val appContext = context.applicationContext
        Attachments.parse(task.attachments).forEach { att ->
            if (AttachmentStore.looksLikeId(att.data)) AttachmentStore.delete(appContext, att.data)
        }
        ReminderScheduler.cancel(appContext, task.id)
        db.taskDao().delete(task)
    }
}
