package com.lucent.app.data

import kotlinx.coroutines.flow.Flow
import java.sql.ResultSet

// Desktop twins of the Android Room DAOs (app/.../data/Daos.kt), method-for-method: same names,
// same signatures, same SQL semantics (the queries are copied from the Room annotations), same
// reactive behaviour through Db.watch. Everything that calls a DAO — the assistant tools, backup,
// search, every screen — compiles against these exactly as it does against Room.

private fun noteOf(rs: ResultSet) = Note(
    id = rs.getLong("id"),
    title = rs.getString("title"),
    body = rs.getString("body"),
    updatedAt = rs.getLong("updatedAt"),
    tags = rs.getString("tags"),
    attachments = rs.getString("attachments"),
    archived = rs.getInt("archived") != 0,
    archivedAt = rs.longOrNull("archivedAt"),
    pinned = rs.getInt("pinned") != 0,
    color = rs.getString("color"),
    isChecklist = rs.getInt("isChecklist") != 0,
    checklist = rs.getString("checklist"),
    trashedAt = rs.longOrNull("trashedAt")
)

private fun taskOf(rs: ResultSet) = Task(
    id = rs.getLong("id"),
    title = rs.getString("title"),
    isDone = rs.getInt("isDone") != 0,
    createdAt = rs.getLong("createdAt"),
    attachments = rs.getString("attachments"),
    dueAt = rs.longOrNull("dueAt"),
    notes = rs.getString("notes"),
    completedAt = rs.longOrNull("completedAt"),
    priority = rs.getInt("priority"),
    pinned = rs.getInt("pinned") != 0,
    subtasks = rs.getString("subtasks"),
    repeatRule = rs.getString("repeatRule"),
    reminderEnabled = rs.getInt("reminderEnabled") != 0,
    trashedAt = rs.longOrNull("trashedAt")
)

private fun versionOf(rs: ResultSet) = NoteVersion(
    id = rs.getLong("id"),
    noteId = rs.getLong("noteId"),
    title = rs.getString("title"),
    body = rs.getString("body"),
    tags = rs.getString("tags"),
    isChecklist = rs.getInt("isChecklist") != 0,
    checklist = rs.getString("checklist"),
    savedAt = rs.getLong("savedAt")
)

private fun messageOf(rs: ResultSet) = ChatMessage(
    id = rs.getLong("id"),
    role = rs.getString("role"),
    content = rs.getString("content"),
    timestamp = rs.getLong("timestamp"),
    attachmentMime = rs.stringOrNull("attachmentMime"),
    attachmentData = rs.stringOrNull("attachmentData"),
    attachmentName = rs.stringOrNull("attachmentName"),
    conversationId = rs.getLong("conversationId"),
    tokens = rs.getInt("tokens")
)

private fun conversationOf(rs: ResultSet) = ChatConversation(
    id = rs.getLong("id"),
    title = rs.getString("title"),
    createdAt = rs.getLong("createdAt"),
    updatedAt = rs.getLong("updatedAt")
)

class NoteDao internal constructor(private val db: Db) {

    fun getAll(): Flow<List<Note>> = db.watch("notes") { getAllActiveOnce() }

    fun getArchived(): Flow<List<Note>> = db.watch("notes") {
        db.use { c ->
            c.prepareStatement(
                "SELECT * FROM notes WHERE archived = 1 AND trashedAt IS NULL " +
                    "ORDER BY COALESCE(archivedAt, updatedAt) DESC"
            ).executeQuery().mapAll(::noteOf)
        }
    }

    fun getTrashed(): Flow<List<Note>> = db.watch("notes") {
        db.use { c ->
            c.prepareStatement("SELECT * FROM notes WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
                .executeQuery().mapAll(::noteOf)
        }
    }

    private suspend fun getAllActiveOnce(): List<Note> = db.use { c ->
        c.prepareStatement("SELECT * FROM notes WHERE archived = 0 AND trashedAt IS NULL ORDER BY updatedAt DESC")
            .executeQuery().mapAll(::noteOf)
    }

    suspend fun getAllOnce(): List<Note> = db.use { c ->
        c.prepareStatement("SELECT * FROM notes ORDER BY updatedAt DESC").executeQuery().mapAll(::noteOf)
    }

    suspend fun getByIdOnce(id: Long): Note? = db.use { c ->
        c.prepareStatement("SELECT * FROM notes WHERE id = ?").apply { setLong(1, id) }
            .executeQuery().mapAll(::noteOf).firstOrNull()
    }

    suspend fun searchNotes(text: String, tag: String, archived: Int, trashed: Int, limit: Int): List<Note> =
        db.use { c ->
            c.prepareStatement(
                """
                SELECT * FROM notes
                WHERE (? = ''
                        OR title LIKE '%' || ? || '%'
                        OR body LIKE '%' || ? || '%'
                        OR tags LIKE '%' || ? || '%'
                        OR checklist LIKE '%' || ? || '%')
                  AND (? = '' OR tags LIKE '%' || ? || '%')
                  AND (? = -1 OR archived = ?)
                  AND (? = -1
                        OR (? = 1 AND trashedAt IS NOT NULL)
                        OR (? = 0 AND trashedAt IS NULL))
                ORDER BY pinned DESC, updatedAt DESC
                LIMIT ?
                """.trimIndent()
            ).apply {
                setString(1, text); setString(2, text); setString(3, text); setString(4, text); setString(5, text)
                setString(6, tag); setString(7, tag)
                setInt(8, archived); setInt(9, archived)
                setInt(10, trashed); setInt(11, trashed); setInt(12, trashed)
                setInt(13, limit)
            }.executeQuery().mapAll(::noteOf)
        }

    suspend fun insert(note: Note): Long = db.write("notes") { c ->
        val ps = c.prepareStatement(
            "INSERT INTO notes (title, body, updatedAt, tags, attachments, archived, archivedAt, " +
                "pinned, color, isChecklist, checklist, trashedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        )
        ps.setString(1, note.title); ps.setString(2, note.body); ps.setLong(3, note.updatedAt)
        ps.setString(4, note.tags); ps.setString(5, note.attachments)
        ps.setInt(6, if (note.archived) 1 else 0); ps.bindLongOrNull(7, note.archivedAt)
        ps.setInt(8, if (note.pinned) 1 else 0); ps.setString(9, note.color)
        ps.setInt(10, if (note.isChecklist) 1 else 0); ps.setString(11, note.checklist)
        ps.bindLongOrNull(12, note.trashedAt)
        ps.executeUpdate()
        ps.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else 0L }
    }

    suspend fun update(note: Note) {
        db.write("notes") { c ->
            val ps = c.prepareStatement(
                "UPDATE notes SET title=?, body=?, updatedAt=?, tags=?, attachments=?, archived=?, " +
                    "archivedAt=?, pinned=?, color=?, isChecklist=?, checklist=?, trashedAt=? WHERE id=?"
            )
            ps.setString(1, note.title); ps.setString(2, note.body); ps.setLong(3, note.updatedAt)
            ps.setString(4, note.tags); ps.setString(5, note.attachments)
            ps.setInt(6, if (note.archived) 1 else 0); ps.bindLongOrNull(7, note.archivedAt)
            ps.setInt(8, if (note.pinned) 1 else 0); ps.setString(9, note.color)
            ps.setInt(10, if (note.isChecklist) 1 else 0); ps.setString(11, note.checklist)
            ps.bindLongOrNull(12, note.trashedAt); ps.setLong(13, note.id)
            ps.executeUpdate()
        }
    }

    suspend fun delete(note: Note) {
        db.write("notes") { c ->
            c.prepareStatement("DELETE FROM notes WHERE id=?").apply { setLong(1, note.id) }.executeUpdate()
        }
    }

    suspend fun clearAll() {
        db.write("notes") { c -> c.createStatement().use { it.executeUpdate("DELETE FROM notes") } }
    }
}

class NoteVersionDao internal constructor(private val db: Db) {

    fun getForNote(noteId: Long): Flow<List<NoteVersion>> = db.watch("note_versions") { getForNoteOnce(noteId) }

    suspend fun getForNoteOnce(noteId: Long): List<NoteVersion> = db.use { c ->
        c.prepareStatement("SELECT * FROM note_versions WHERE noteId = ? ORDER BY savedAt DESC")
            .apply { setLong(1, noteId) }.executeQuery().mapAll(::versionOf)
    }

    suspend fun getAllOnce(): List<NoteVersion> = db.use { c ->
        c.prepareStatement("SELECT * FROM note_versions ORDER BY savedAt DESC").executeQuery().mapAll(::versionOf)
    }

    suspend fun countForNote(noteId: Long): Int = db.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM note_versions WHERE noteId = ?")
            .apply { setLong(1, noteId) }.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    suspend fun insert(version: NoteVersion): Long = db.write("note_versions") { c ->
        val ps = c.prepareStatement(
            "INSERT INTO note_versions (noteId, title, body, tags, isChecklist, checklist, savedAt) " +
                "VALUES (?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        )
        ps.setLong(1, version.noteId); ps.setString(2, version.title); ps.setString(3, version.body)
        ps.setString(4, version.tags); ps.setInt(5, if (version.isChecklist) 1 else 0)
        ps.setString(6, version.checklist); ps.setLong(7, version.savedAt)
        ps.executeUpdate()
        ps.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else 0L }
    }

    suspend fun delete(version: NoteVersion) {
        db.write("note_versions") { c ->
            c.prepareStatement("DELETE FROM note_versions WHERE id=?").apply { setLong(1, version.id) }.executeUpdate()
        }
    }

    suspend fun deleteForNote(noteId: Long) {
        db.write("note_versions") { c ->
            c.prepareStatement("DELETE FROM note_versions WHERE noteId=?").apply { setLong(1, noteId) }.executeUpdate()
        }
    }

    suspend fun trimTo(noteId: Long, keep: Int) {
        db.write("note_versions") { c ->
            c.prepareStatement(
                "DELETE FROM note_versions WHERE noteId = ? AND id NOT IN (" +
                    "SELECT id FROM note_versions WHERE noteId = ? ORDER BY savedAt DESC LIMIT ?)"
            ).apply { setLong(1, noteId); setLong(2, noteId); setInt(3, keep) }.executeUpdate()
        }
    }

    suspend fun pruneOrphaned() {
        db.write("note_versions") { c ->
            c.createStatement().use {
                it.executeUpdate("DELETE FROM note_versions WHERE noteId NOT IN (SELECT id FROM notes)")
            }
        }
    }

    suspend fun clearAll() {
        db.write("note_versions") { c -> c.createStatement().use { it.executeUpdate("DELETE FROM note_versions") } }
    }
}

class TaskDao internal constructor(private val db: Db) {

    fun getAll(): Flow<List<Task>> = db.watch("tasks") { getAllOnce() }

    suspend fun getAllOnce(): List<Task> = db.use { c ->
        c.prepareStatement("SELECT * FROM tasks ORDER BY createdAt DESC").executeQuery().mapAll(::taskOf)
    }

    suspend fun getByIdOnce(id: Long): Task? = db.use { c ->
        c.prepareStatement("SELECT * FROM tasks WHERE id = ?").apply { setLong(1, id) }
            .executeQuery().mapAll(::taskOf).firstOrNull()
    }

    suspend fun searchTasks(
        text: String,
        done: Int,
        trashed: Int,
        minPriority: Int,
        dueBefore: Long,
        dueAfter: Long,
        limit: Int
    ): List<Task> = db.use { c ->
        c.prepareStatement(
            """
            SELECT * FROM tasks
            WHERE (? = ''
                    OR title LIKE '%' || ? || '%'
                    OR notes LIKE '%' || ? || '%'
                    OR subtasks LIKE '%' || ? || '%')
              AND (? = -1 OR isDone = ?)
              AND (? = -1
                    OR (? = 1 AND trashedAt IS NOT NULL)
                    OR (? = 0 AND trashedAt IS NULL))
              AND (? = -1 OR priority >= ?)
              AND (? = -1 OR (dueAt IS NOT NULL AND dueAt <= ?))
              AND (? = -1 OR (dueAt IS NOT NULL AND dueAt >= ?))
            ORDER BY pinned DESC, priority DESC, COALESCE(dueAt, 9223372036854775807) ASC, createdAt DESC
            LIMIT ?
            """.trimIndent()
        ).apply {
            setString(1, text); setString(2, text); setString(3, text); setString(4, text)
            setInt(5, done); setInt(6, done)
            setInt(7, trashed); setInt(8, trashed); setInt(9, trashed)
            setInt(10, minPriority); setInt(11, minPriority)
            setLong(12, dueBefore); setLong(13, dueBefore)
            setLong(14, dueAfter); setLong(15, dueAfter)
            setInt(16, limit)
        }.executeQuery().mapAll(::taskOf)
    }

    fun getActive(): Flow<List<Task>> = db.watch("tasks") {
        db.use { c ->
            c.prepareStatement("SELECT * FROM tasks WHERE isDone = 0 AND trashedAt IS NULL ORDER BY createdAt DESC")
                .executeQuery().mapAll(::taskOf)
        }
    }

    suspend fun activeCountOnce(): Int = db.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM tasks WHERE isDone = 0 AND trashedAt IS NULL")
            .executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    fun getCompleted(): Flow<List<Task>> = db.watch("tasks") {
        db.use { c ->
            c.prepareStatement(
                "SELECT * FROM tasks WHERE isDone = 1 AND trashedAt IS NULL " +
                    "ORDER BY COALESCE(completedAt, createdAt) DESC"
            ).executeQuery().mapAll(::taskOf)
        }
    }

    fun getTrashed(): Flow<List<Task>> = db.watch("tasks") {
        db.use { c ->
            c.prepareStatement("SELECT * FROM tasks WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
                .executeQuery().mapAll(::taskOf)
        }
    }

    suspend fun insert(task: Task): Long = db.write("tasks") { c ->
        val ps = c.prepareStatement(
            "INSERT INTO tasks (title, isDone, createdAt, attachments, dueAt, notes, completedAt, " +
                "priority, pinned, subtasks, repeatRule, reminderEnabled, trashedAt) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        )
        ps.setString(1, task.title); ps.setInt(2, if (task.isDone) 1 else 0); ps.setLong(3, task.createdAt)
        ps.setString(4, task.attachments); ps.bindLongOrNull(5, task.dueAt); ps.setString(6, task.notes)
        ps.bindLongOrNull(7, task.completedAt); ps.setInt(8, task.priority)
        ps.setInt(9, if (task.pinned) 1 else 0); ps.setString(10, task.subtasks)
        ps.setString(11, task.repeatRule); ps.setInt(12, if (task.reminderEnabled) 1 else 0)
        ps.bindLongOrNull(13, task.trashedAt)
        ps.executeUpdate()
        ps.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else 0L }
    }

    suspend fun update(task: Task) {
        db.write("tasks") { c ->
            val ps = c.prepareStatement(
                "UPDATE tasks SET title=?, isDone=?, createdAt=?, attachments=?, dueAt=?, notes=?, " +
                    "completedAt=?, priority=?, pinned=?, subtasks=?, repeatRule=?, reminderEnabled=?, " +
                    "trashedAt=? WHERE id=?"
            )
            ps.setString(1, task.title); ps.setInt(2, if (task.isDone) 1 else 0); ps.setLong(3, task.createdAt)
            ps.setString(4, task.attachments); ps.bindLongOrNull(5, task.dueAt); ps.setString(6, task.notes)
            ps.bindLongOrNull(7, task.completedAt); ps.setInt(8, task.priority)
            ps.setInt(9, if (task.pinned) 1 else 0); ps.setString(10, task.subtasks)
            ps.setString(11, task.repeatRule); ps.setInt(12, if (task.reminderEnabled) 1 else 0)
            ps.bindLongOrNull(13, task.trashedAt); ps.setLong(14, task.id)
            ps.executeUpdate()
        }
    }

    suspend fun delete(task: Task) {
        db.write("tasks") { c ->
            c.prepareStatement("DELETE FROM tasks WHERE id=?").apply { setLong(1, task.id) }.executeUpdate()
        }
    }

    suspend fun clearAll() {
        db.write("tasks") { c -> c.createStatement().use { it.executeUpdate("DELETE FROM tasks") } }
    }
}

/** Projection for [ChatDao.conversationContents] — see the Android original. */
data class ConversationContent(
    val conversationId: Long,
    val content: String?
)

class ChatDao internal constructor(private val db: Db) {

    fun getAll(): Flow<List<ChatMessage>> = db.watch("chat_messages") { getAllOnce() }

    suspend fun getAllOnce(): List<ChatMessage> = db.use { c ->
        c.prepareStatement("SELECT * FROM chat_messages ORDER BY timestamp ASC").executeQuery().mapAll(::messageOf)
    }

    fun getForConversation(conversationId: Long): Flow<List<ChatMessage>> =
        db.watch("chat_messages") { getForConversationOnce(conversationId) }

    suspend fun getForConversationOnce(conversationId: Long): List<ChatMessage> = db.use { c ->
        c.prepareStatement("SELECT * FROM chat_messages WHERE conversationId = ? ORDER BY timestamp ASC")
            .apply { setLong(1, conversationId) }.executeQuery().mapAll(::messageOf)
    }

    suspend fun countInConversation(conversationId: Long): Int = db.use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM chat_messages WHERE conversationId = ?")
            .apply { setLong(1, conversationId) }.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    suspend fun conversationContents(): List<ConversationContent> = db.use { c ->
        c.prepareStatement(
            "SELECT conversationId AS conversationId, GROUP_CONCAT(content, ' ') AS content " +
                "FROM chat_messages GROUP BY conversationId"
        ).executeQuery().mapAll { rs -> ConversationContent(rs.getLong("conversationId"), rs.stringOrNull("content")) }
    }

    suspend fun insert(message: ChatMessage) {
        db.write("chat_messages") { c ->
            val ps = c.prepareStatement(
                "INSERT INTO chat_messages (role, content, timestamp, attachmentMime, attachmentData, " +
                    "attachmentName, conversationId, tokens) VALUES (?,?,?,?,?,?,?,?)"
            )
            ps.setString(1, message.role); ps.setString(2, message.content); ps.setLong(3, message.timestamp)
            ps.bindStringOrNull(4, message.attachmentMime); ps.bindStringOrNull(5, message.attachmentData)
            ps.bindStringOrNull(6, message.attachmentName); ps.setLong(7, message.conversationId)
            ps.setInt(8, message.tokens)
            ps.executeUpdate()
        }
    }

    suspend fun clearConversation(conversationId: Long) {
        db.write("chat_messages") { c ->
            c.prepareStatement("DELETE FROM chat_messages WHERE conversationId = ?")
                .apply { setLong(1, conversationId) }.executeUpdate()
        }
    }

    suspend fun clearAll() {
        db.write("chat_messages") { c -> c.createStatement().use { it.executeUpdate("DELETE FROM chat_messages") } }
    }
}

class ChatConversationDao internal constructor(private val db: Db) {

    fun getAll(): Flow<List<ChatConversation>> = db.watch("chat_conversations") { getAllOnce() }

    suspend fun getAllOnce(): List<ChatConversation> = db.use { c ->
        c.prepareStatement("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
            .executeQuery().mapAll(::conversationOf)
    }

    suspend fun getById(id: Long): ChatConversation? = db.use { c ->
        c.prepareStatement("SELECT * FROM chat_conversations WHERE id = ?").apply { setLong(1, id) }
            .executeQuery().mapAll(::conversationOf).firstOrNull()
    }

    suspend fun insert(conversation: ChatConversation): Long = db.write("chat_conversations") { c ->
        val ps = c.prepareStatement(
            "INSERT INTO chat_conversations (title, createdAt, updatedAt) VALUES (?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        )
        ps.setString(1, conversation.title); ps.setLong(2, conversation.createdAt); ps.setLong(3, conversation.updatedAt)
        ps.executeUpdate()
        ps.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else 0L }
    }

    suspend fun update(conversation: ChatConversation) {
        db.write("chat_conversations") { c ->
            c.prepareStatement("UPDATE chat_conversations SET title=?, createdAt=?, updatedAt=? WHERE id=?")
                .apply {
                    setString(1, conversation.title); setLong(2, conversation.createdAt)
                    setLong(3, conversation.updatedAt); setLong(4, conversation.id)
                }.executeUpdate()
        }
    }

    suspend fun delete(conversation: ChatConversation) {
        db.write("chat_conversations") { c ->
            c.prepareStatement("DELETE FROM chat_conversations WHERE id=?")
                .apply { setLong(1, conversation.id) }.executeUpdate()
        }
    }

    suspend fun clearAll() {
        db.write("chat_conversations") { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM chat_conversations") }
        }
    }
}
