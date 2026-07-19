package com.lucent.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    /**
     * Active (non-archived, non-trashed) notes only — this powers the Notes home page, so once a
     * note is archived (or trashed) it disappears from there. Ordered by most-recently-updated
     * first; the UI then applies the user's chosen sort on top (see ui/SortOptions.kt), with
     * pinned notes floating above it.
     */
    @Query("SELECT * FROM notes WHERE archived = 0 AND trashedAt IS NULL ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<Note>>

    /**
     * Archived, non-trashed notes only — powers the dedicated archive screen. Sorted by when they
     * were archived (most recent first); falls back to updatedAt for any row archived before we
     * started recording archivedAt.
     */
    @Query("SELECT * FROM notes WHERE archived = 1 AND trashedAt IS NULL ORDER BY COALESCE(archivedAt, updatedAt) DESC")
    fun getArchived(): Flow<List<Note>>

    /** Trashed notes only — powers the Trash screen. Most-recently-trashed first. */
    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun getTrashed(): Flow<List<Note>>

    /**
     * One-shot snapshot of *every* note — archived and trashed included. Preferred over
     * `getAll().first()` in non-UI code (e.g. the assistant's tools): collecting a Room Flow
     * registers and tears down an invalidation observer each time, which adds noticeable latency
     * when a tool reads notes several times in a turn. A direct suspend query skips that overhead.
     *
     * Deliberately unfiltered: callers that maintain invariants across the whole table — the
     * attachment migration, the orphan sweep, backups, the trash sweep — must see archived and
     * trashed rows too, or they would treat a still-referenced attachment as an orphan and delete
     * it. Callers that want only what the user can currently see (the assistant's tools) filter
     * `trashedAt == null` themselves; see AppTools.activeNotes.
     */
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getByIdOnce(id: Long): Note?

    /**
     * The coarse half of a search, run **inside SQLite** rather than over an in-memory list.
     *
     * The global search screen looks at every note there is — active, archived, and trashed — which is
     * the one query that genuinely must not materialise the whole table in Kotlin first. So the text
     * `LIKE` and the structural filters happen here, as SQL predicates, and the caller then applies
     * the parts SQL can't express (phrases, `has:`, `link:`, relevance ranking) to the small candidate
     * set that comes back.
     *
     * `LIKE '%x%'` and not an FTS index, deliberately: no built-in FTS tokeniser segments Chinese,
     * Japanese, or Korean, so an index would silently stop matching CJK substrings. `LIKE` behaves the
     * same in every script. See [SearchQuery] for the full argument.
     *
     * Every parameter has an "ignore me" value, because Room cannot build a query dynamically:
     *  - [text] / [tag]: `''` disables the clause
     *  - [archived]: 1 = archived only, 0 = not archived, `-1` = either
     *  - [trashed]: 1 = trashed only, 0 = not trashed, `-1` = either
     *
     * The filter is deliberately *permissive*. It must never drop a row the Kotlin matcher would have
     * kept — being generous costs a few extra rows to scan; being strict loses the user's note.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE (:text = ''
                OR title LIKE '%' || :text || '%'
                OR body LIKE '%' || :text || '%'
                OR tags LIKE '%' || :text || '%'
                OR checklist LIKE '%' || :text || '%')
          AND (:tag = '' OR tags LIKE '%' || :tag || '%')
          AND (:archived = -1 OR archived = :archived)
          AND (:trashed = -1
                OR (:trashed = 1 AND trashedAt IS NOT NULL)
                OR (:trashed = 0 AND trashedAt IS NULL))
        ORDER BY pinned DESC, updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchNotes(
        text: String,
        tag: String,
        archived: Int,
        trashed: Int,
        limit: Int
    ): List<Note>

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("DELETE FROM notes")
    suspend fun clearAll()
}

/**
 * Local, on-device revision history for notes (see [NoteVersion]).
 *
 * Every query is scoped to a single note, and [trimTo] enforces the per-note cap so a heavily
 * edited note can't quietly grow an unbounded history table.
 */
@Dao
interface NoteVersionDao {
    /** Newest revision first — the order the history screen renders. */
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY savedAt DESC")
    fun getForNote(noteId: Long): Flow<List<NoteVersion>>

    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY savedAt DESC")
    suspend fun getForNoteOnce(noteId: Long): List<NoteVersion>

    @Query("SELECT * FROM note_versions ORDER BY savedAt DESC")
    suspend fun getAllOnce(): List<NoteVersion>

    @Query("SELECT COUNT(*) FROM note_versions WHERE noteId = :noteId")
    suspend fun countForNote(noteId: Long): Int

    @Insert
    suspend fun insert(version: NoteVersion): Long

    @Delete
    suspend fun delete(version: NoteVersion)

    /** Drop every revision of one note — used when the note is permanently deleted. */
    @Query("DELETE FROM note_versions WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)

    /**
     * Keep only the [keep] most recent revisions of a note and delete the rest. Written as a single
     * statement so it stays atomic and can't leave history half-trimmed if the process dies partway.
     */
    @Query(
        "DELETE FROM note_versions WHERE noteId = :noteId AND id NOT IN (" +
            "SELECT id FROM note_versions WHERE noteId = :noteId ORDER BY savedAt DESC LIMIT :keep)"
    )
    suspend fun trimTo(noteId: Long, keep: Int)

    /**
     * Delete revisions belonging to notes that no longer exist. A safety sweep only: every delete
     * path already clears its own history, so in practice this finds nothing — but it means a
     * hand-edited database or an interrupted delete can't leave history rows stranded forever.
     */
    @Query("DELETE FROM note_versions WHERE noteId NOT IN (SELECT id FROM notes)")
    suspend fun pruneOrphaned()

    @Query("DELETE FROM note_versions")
    suspend fun clearAll()
}

@Dao
interface TaskDao {
    /**
     * Every task — done, pending, and trashed alike. The home list no longer uses this (it uses
     * [getActive]), but backups, the attachment orphan sweep, the trash sweep, and reminder
     * rescheduling all legitimately want "all tasks", so this stays unfiltered for the same reason
     * [NoteDao.getAllOnce] does.
     */
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Task>>

    /** One-shot snapshot of all tasks — same rationale as [NoteDao.getAllOnce]. */
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getByIdOnce(id: Long): Task?

    /**
     * The task side of the global search. Same reasoning as [NoteDao.searchNotes]: SQL narrows,
     * Kotlin refines.
     *
     *  - [done]: 1 = completed only, 0 = pending only, `-1` = either
     *  - [trashed]: 1 = trashed only, 0 = not trashed, `-1` = either
     *  - [minPriority]: a floor, or `-1`
     *  - [dueBefore] / [dueAfter]: bound `dueAt`; `-1` disables. A task with **no** due date never
     *    matches either bound, which is right — "due this week" should not surface a task that isn't
     *    on the calendar at all.
     *
     * Ordered so the answer is useful before the caller has ranked anything: pinned first, then by
     * priority, then by whichever deadline is closest — with undated tasks pushed to the very end via
     * `COALESCE(dueAt, <max long>)` rather than sorting as if they were due at the dawn of time.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE (:text = ''
                OR title LIKE '%' || :text || '%'
                OR notes LIKE '%' || :text || '%'
                OR subtasks LIKE '%' || :text || '%')
          AND (:done = -1 OR isDone = :done)
          AND (:trashed = -1
                OR (:trashed = 1 AND trashedAt IS NOT NULL)
                OR (:trashed = 0 AND trashedAt IS NULL))
          AND (:minPriority = -1 OR priority >= :minPriority)
          AND (:dueBefore = -1 OR (dueAt IS NOT NULL AND dueAt <= :dueBefore))
          AND (:dueAfter = -1 OR (dueAt IS NOT NULL AND dueAt >= :dueAfter))
        ORDER BY pinned DESC, priority DESC, COALESCE(dueAt, 9223372036854775807) ASC, createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchTasks(
        text: String,
        done: Int,
        trashed: Int,
        minPriority: Int,
        dueBefore: Long,
        dueAfter: Long,
        limit: Int
    ): List<Task>

    /**
     * Pending, non-trashed tasks only. Powers the Tasks home page after the completed-history
     * split (and now the Trash split too). Ordered newest-first here; the UI applies the user's
     * chosen sort on top, with pinned tasks floating above it.
     */
    @Query("SELECT * FROM tasks WHERE isDone = 0 AND trashedAt IS NULL ORDER BY createdAt DESC")
    fun getActive(): Flow<List<Task>>

    // A cheap count of active (not done, not trashed) tasks — used by the home-screen summary widget
    // (task 9) so it can show a number without loading every row.
    @Query("SELECT COUNT(*) FROM tasks WHERE isDone = 0 AND trashedAt IS NULL")
    suspend fun activeCountOnce(): Int

    /**
     * Completed, non-trashed tasks only. Powers the completed-tasks history page. Sorted by
     * completion time so the most recently finished tasks sit at the top; falls back to createdAt
     * for older rows that were completed before we started recording completedAt.
     */
    @Query("SELECT * FROM tasks WHERE isDone = 1 AND trashedAt IS NULL ORDER BY COALESCE(completedAt, createdAt) DESC")
    fun getCompleted(): Flow<List<Task>>

    /** Trashed tasks only (done or pending) — powers the Trash screen. Most-recently-trashed first. */
    @Query("SELECT * FROM tasks WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun getTrashed(): Flow<List<Task>>

    @Insert
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    @Query("DELETE FROM tasks")
    suspend fun clearAll()
}

/**
 * Projection for [ChatDao.conversationContents]: a conversation id paired with all of its message
 * text concatenated. Powers the deep history search (issue 3) without materialising message rows.
 * [content] can be null for a conversation with no messages, which the caller treats as empty.
 */
data class ConversationContent(
    val conversationId: Long,
    val content: String?
)

@Dao
interface ChatDao {
    /**
     * All chat messages across every conversation, oldest first. Still used by backup/export and
     * the search-everything path; the assistant screen itself now observes a single conversation
     * via [getForConversation].
     */
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAll(): Flow<List<ChatMessage>>

    /**
     * Snapshot (non-Flow) of every message across all conversations, oldest first. Used by the
     * history search to find each individual place the query occurs (task 9); it reads message
     * bodies directly rather than the concatenated-per-conversation projection so it can report the
     * exact message and offset of every hit.
     */
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<ChatMessage>

    /** Messages in one conversation, oldest first — what the assistant screen renders. */
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getForConversation(conversationId: Long): Flow<List<ChatMessage>>

    /** Snapshot (non-Flow) of one conversation's messages, used when building an API request. */
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getForConversationOnce(conversationId: Long): List<ChatMessage>

    /** Number of messages in a conversation — used to sweep empty conversation rows. */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun countInConversation(conversationId: Long): Int

    /**
     * One row per conversation holding all of that conversation's message text concatenated, for the
     * deep history search (issue 3). GROUP_CONCAT keeps the whole join inside SQLite, so the search
     * index is built in one query instead of pulling every message row into Kotlin. See
     * data/ChatSearch.kt for how the ranking then runs over these.
     */
    @Query("SELECT conversationId AS conversationId, GROUP_CONCAT(content, ' ') AS content FROM chat_messages GROUP BY conversationId")
    suspend fun conversationContents(): List<ConversationContent>

    @Insert
    suspend fun insert(message: ChatMessage)

    /** Delete just one conversation's messages (used when clearing / deleting that conversation). */
    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: Long)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface ChatConversationDao {
    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ChatConversation>>

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<ChatConversation>

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun getById(id: Long): ChatConversation?

    @Insert
    suspend fun insert(conversation: ChatConversation): Long

    @Update
    suspend fun update(conversation: ChatConversation)

    @Delete
    suspend fun delete(conversation: ChatConversation)

    @Query("DELETE FROM chat_conversations")
    suspend fun clearAll()
}
