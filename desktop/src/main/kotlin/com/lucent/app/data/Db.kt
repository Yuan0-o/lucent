package com.lucent.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The desktop persistence core: one SQLite connection, serialized access, and Room-style
 * "invalidation" flows so the DAO surface in Daos.kt can offer the exact same reactive API the
 * Android app gets from Room.
 *
 * ### Encryption at rest
 *
 * The bundled JDBC driver is `io.github.willena:sqlite-jdbc` — a drop-in build of the Xerial driver
 * whose SQLite core is SQLite3MultipleCiphers, which speaks the **SQLCipher** scheme. The database
 * is keyed with the same raw-key form the Android build feeds SQLCipher (`x'<64 hex>'`, minted and
 * stored by [DataKeys]), so the desktop database enjoys the same at-rest encryption the phone has.
 * If the cipher PRAGMAs are ever unavailable (a swapped-in plain driver), the store degrades to an
 * unencrypted file with a loud [StartupLog] entry rather than refusing to start — the same
 * "degrade one notch, never strand the data" policy the Android key handling follows.
 *
 * ### Why one connection and a mutex
 *
 * SQLite serializes writers anyway; funnelling every statement through one connection guarded by a
 * [Mutex] makes cross-thread misuse impossible by construction (the same reasoning LocalLlm applies
 * to its native session) and keeps transactions trivial. All calls run on [Dispatchers.IO].
 */
class Db private constructor(private val connection: Connection) {

    private val mutex = Mutex()

    // Table-change bus. extraBufferCapacity keeps emitters from suspending; watchers conflate, so a
    // burst of writes collapses into one re-query — exactly Room's invalidation behaviour.
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Run [block] with exclusive access to the connection, off the caller's thread. */
    suspend fun <T> use(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(connection) }
    }

    /** Run [block] like [use], then announce that [tables] changed so watchers re-query. */
    suspend fun <T> write(vararg tables: String, block: (Connection) -> T): T {
        val result = use(block)
        tables.forEach { changes.tryEmit(it) }
        return result
    }

    /**
     * A cold flow that emits [query]'s result immediately and again whenever any of [tables]
     * changes. Conflated and de-duplicated, mirroring Room's Flow queries closely enough that the
     * shared screens can't tell the difference.
     */
    fun <T> watch(vararg tables: String, query: suspend () -> T): Flow<T> =
        changes
            .filter { it in tables }
            .onStart { emit(tables.first()) }
            .map { query() }
            .distinctUntilChanged()
            .conflate()
            .flowOn(Dispatchers.IO)

    companion object {

        /** Schema version this build writes. Matches the Android Room schema (version 11). */
        private const val SCHEMA_VERSION = 11

        fun open(context: Context): Db {
            val file = File(context.applicationContext.filesDir, "lucent.db")
            file.parentFile?.mkdirs()
            // Load the driver class explicitly so a missing dependency fails with a clear message.
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
            applyEncryption(context, conn, file)
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA foreign_keys=ON")
            }
            createSchema(conn)
            return Db(conn)
        }

        /**
         * Key the connection with the SQLCipher-compatible raw key before anything reads or writes.
         * A wrong key (or a database written with a different key) surfaces on the probe query as a
         * "file is not a database" error, which we rethrow with a message a person can act on.
         */
        private fun applyEncryption(context: Context, conn: Connection, file: File) {
            val passphrase = try {
                DataKeys.databasePassphrase(context) // "x'<64 hex>'"
            } catch (t: Throwable) {
                StartupLog.event(context, "db: key unavailable (${t.message}); opening unencrypted")
                return
            }
            val cipherReady = try {
                conn.createStatement().use { st ->
                    st.execute("PRAGMA cipher='sqlcipher'")
                    st.execute("PRAGMA legacy=4")
                    // The x'..' raw-key form skips the KDF, same as SQLCipher on Android.
                    st.execute("PRAGMA key=\"$passphrase\"")
                }
                true
            } catch (t: Throwable) {
                StartupLog.event(context, "db: cipher pragmas unavailable (${t.message}); opening unencrypted")
                false
            }
            if (!cipherReady) return
            try {
                conn.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_master").close() }
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "The Lucent database at ${file.absolutePath} could not be unlocked with this " +
                        "machine's key. If the key files under ${File(context.filesDir, "keys")} were " +
                        "deleted or replaced, restore from a .lcb backup.", t
                )
            }
        }

        /**
         * Create every table and index at the shape Room's schema v11 has on Android — same names,
         * same columns, same defaults — so the two stores stay structurally interchangeable.
         * Idempotent: IF NOT EXISTS everywhere, and user_version records what is on disk.
         */
        private fun createSchema(conn: Connection) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS notes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "body TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "tags TEXT NOT NULL DEFAULT '', " +
                        "attachments TEXT NOT NULL DEFAULT '[]', " +
                        "archived INTEGER NOT NULL DEFAULT 0, " +
                        "archivedAt INTEGER, " +
                        "pinned INTEGER NOT NULL DEFAULT 0, " +
                        "color TEXT NOT NULL DEFAULT '', " +
                        "isChecklist INTEGER NOT NULL DEFAULT 0, " +
                        "checklist TEXT NOT NULL DEFAULT '[]', " +
                        "trashedAt INTEGER)"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS tasks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "isDone INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL, " +
                        "attachments TEXT NOT NULL DEFAULT '[]', " +
                        "dueAt INTEGER, " +
                        "notes TEXT NOT NULL DEFAULT '', " +
                        "completedAt INTEGER, " +
                        "priority INTEGER NOT NULL DEFAULT 0, " +
                        "pinned INTEGER NOT NULL DEFAULT 0, " +
                        "subtasks TEXT NOT NULL DEFAULT '[]', " +
                        "repeatRule TEXT NOT NULL DEFAULT 'NONE', " +
                        "reminderEnabled INTEGER NOT NULL DEFAULT 0, " +
                        "trashedAt INTEGER)"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS note_versions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "noteId INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "body TEXT NOT NULL, " +
                        "tags TEXT NOT NULL DEFAULT '', " +
                        "isChecklist INTEGER NOT NULL DEFAULT 0, " +
                        "checklist TEXT NOT NULL DEFAULT '[]', " +
                        "savedAt INTEGER NOT NULL)"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS chat_messages (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "attachmentMime TEXT, " +
                        "attachmentData TEXT, " +
                        "attachmentName TEXT, " +
                        "conversationId INTEGER NOT NULL DEFAULT 1, " +
                        "tokens INTEGER NOT NULL DEFAULT 0)"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS chat_conversations (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                // Same list-query indices Room's schema carries, under Room's own names.
                st.executeUpdate("CREATE INDEX IF NOT EXISTS index_notes_updatedAt ON notes (updatedAt)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS index_notes_archived ON notes (archived)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS index_notes_trashedAt ON notes (trashedAt)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS index_tasks_createdAt ON tasks (createdAt)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS index_tasks_isDone ON tasks (isDone)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS index_tasks_trashedAt ON tasks (trashedAt)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS index_note_versions_noteId ON note_versions (noteId)")
                st.executeUpdate("PRAGMA user_version=$SCHEMA_VERSION")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Small JDBC helpers shared by the DAOs. Kept here so the DAO bodies read as query + mapping.
// ---------------------------------------------------------------------------------------------

internal fun PreparedStatement.bindLongOrNull(index: Int, value: Long?) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setLong(index, value)
}

internal fun PreparedStatement.bindStringOrNull(index: Int, value: String?) {
    if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)
}

internal fun ResultSet.longOrNull(column: String): Long? {
    val v = getLong(column)
    return if (wasNull()) null else v
}

internal fun ResultSet.stringOrNull(column: String): String? = getString(column)

internal fun <T> ResultSet.mapAll(mapper: (ResultSet) -> T): List<T> {
    val out = ArrayList<T>()
    while (next()) out.add(mapper(this))
    close()
    return out
}
