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

        /** The 16-byte header every UNENCRYPTED SQLite file starts with; an encrypted page 1 never has it. */
        private val PLAINTEXT_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

        /** True when [file] exists and carries the plain-SQLite header — i.e. a legacy unencrypted store. */
        private fun isPlaintextDatabase(file: File): Boolean {
            if (!file.exists() || file.length() < PLAINTEXT_HEADER.size) return false
            val head = ByteArray(PLAINTEXT_HEADER.size)
            file.inputStream().use { if (it.read(head) != head.size) return false }
            return head.contentEquals(PLAINTEXT_HEADER)
        }

        /**
         * Key the connection with the SQLCipher-compatible raw key before anything reads or writes.
         *
         * Three cases, told apart by the FILE HEADER before any key is applied — a plaintext SQLite
         * file always begins "SQLite format 3\0", an encrypted one never does:
         *
         *  1. **Fresh or already-encrypted file** — set the cipher pragmas and the key; a probe that
         *     still fails means a genuinely wrong key (the header already ruled out "it's plaintext"),
         *     which is rethrown with a message a person can act on.
         *  2. **Legacy plaintext file** — written by the earlier desktop builds that shipped the plain
         *     org.xerial driver. Encrypted IN PLACE, once, silently: leave WAL (rekey refuses a WAL
         *     database, and every prior run left it in WAL), set the cipher shape, then `PRAGMA rekey`
         *     — SQLite3MultipleCiphers encrypts an unencrypted database in place on rekey. Data is
         *     never copied out, exported, or set aside; a failed rekey degrades to the old unencrypted
         *     behaviour with a loud [StartupLog] line rather than stranding the store.
         *  3. **No cipher core** (a plain driver swapped back in) — detected up front via
         *     `PRAGMA cipher_version`, the one probe stock SQLite cannot fake (it silently ignores
         *     unknown pragmas, so merely "accepting" the key statements proves nothing) — degrade to
         *     unencrypted with a loud log line, exactly as before.
         */
        private fun applyEncryption(context: Context, conn: Connection, file: File) {
            val passphrase = try {
                DataKeys.databasePassphrase(context) // "x'<64 hex>'"
            } catch (t: Throwable) {
                StartupLog.event(context, "db: key unavailable (${t.message}); opening unencrypted")
                return
            }
            val legacyPlaintext = isPlaintextDatabase(file)
            // Establish that the cipher core is actually present before claiming anything. Stock
            // SQLite silently IGNORES unknown PRAGMAs, so a plain driver would "accept" every
            // statement below while writing plaintext. The one probe a plain driver cannot fake is
            // cipher_version, which only SQLite3MultipleCiphers answers with a value.
            val cipherAvailable = try {
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA cipher_version").use { rs -> rs.next() && !rs.getString(1).isNullOrBlank() }
                }
            } catch (t: Throwable) {
                false
            }
            if (!cipherAvailable) {
                StartupLog.event(context, "db: driver has no cipher core; opening UNENCRYPTED")
                return
            }
            val cipherReady = try {
                conn.createStatement().use { st ->
                    if (legacyPlaintext) {
                        // rekey refuses WAL, and prior (unencrypted) runs always left WAL on. The
                        // plaintext file opens fine without a key, so switch the journal first —
                        // this also checkpoints any leftover -wal from the last unencrypted run.
                        st.execute("PRAGMA journal_mode=DELETE")
                    }
                    st.execute("PRAGMA cipher='sqlcipher'")
                    st.execute("PRAGMA legacy=4")
                    if (legacyPlaintext) {
                        // The x'..' raw-key form skips the KDF, same as SQLCipher on Android.
                        st.execute("PRAGMA rekey=\"$passphrase\"")
                    } else {
                        st.execute("PRAGMA key=\"$passphrase\"")
                    }
                }
                true
            } catch (t: Throwable) {
                StartupLog.event(context, "db: keying failed (${t.message}); opening unencrypted")
                false
            }
            if (!cipherReady) return
            try {
                conn.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_master").close() }
                if (legacyPlaintext) StartupLog.event(context, "db: legacy unencrypted database encrypted in place")
            } catch (t: Throwable) {
                if (legacyPlaintext) {
                    // The one-time in-place encryption didn't take on this driver. Never strand the
                    // data: fall back to exactly what every previous build did (plaintext), loudly.
                    StartupLog.event(context, "db: in-place encryption failed (${t.message}); opening unencrypted")
                    return
                }
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
