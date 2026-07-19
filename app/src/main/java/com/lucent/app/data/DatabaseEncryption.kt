package com.lucent.app.data

import android.content.Context
import android.util.Log
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Encrypts the Room database with SQLCipher, and gets an existing plaintext database safely across
 * to it.
 *
 * ### Why the database needs this at all
 *
 * Android already encrypts `/data` at rest, so a stolen, locked phone is not the threat. Two other
 * things are, and both are real for *this* app specifically:
 *
 * 1. **Lucent ships as a debug APK.** That is how the project is built and installed — straight from
 *    a GitHub Actions artefact. A debug build is `android:debuggable`, which means anyone with USB
 *    debugging enabled and the phone in their hand can run `adb shell run-as com.jiaying.yuan.lucentapp`
 *    and read the app's private data directory **without root**. Against that, "the OS encrypts the
 *    partition" is no defence at all: the OS cheerfully decrypts it for you.
 * 2. Android's own cloud backup used to be carrying the whole database off the device. That one is
 *    fixed in the manifest (`allowBackup="false"`), but it is a good illustration of how plaintext
 *    on disk leaks in ways nobody planned.
 *
 * ### The dangerous part, stated plainly
 *
 * Room's `SupportSQLiteOpenHelper.Callback.onCorruption` **deletes the database file**. SQLCipher
 * reports a wrong key as corruption. Wire the two together naively and the first time the key can't
 * be read — a Keystore hiccup, a half-restored install — Android quietly deletes every note the user
 * has, and does it *on their behalf*, as a helpful recovery step.
 *
 * That is the single worst thing this file could do, so it is designed around not doing it:
 *
 * - [ensureReady] **verifies the key opens the database before Room is ever constructed.** Room only
 *   ever receives a database it is already known to be able to decrypt, so its corruption path
 *   cannot fire on a key problem.
 * - If the database genuinely cannot be decrypted, it is **moved aside, never deleted** — and the app
 *   still starts, empty, with a notice pointing at Import. The bytes are still on disk; the user can
 *   restore from a backup rather than staring at a crash loop with no way into Settings.
 * - The plaintext→encrypted migration writes to a **new file** and only swaps it in after verifying
 *   it. If anything at all goes wrong, the original is put back untouched and the app runs
 *   unencrypted this launch, retrying next time. An encryption upgrade must never be able to cost
 *   someone their data — a feature that can lose your notes is worse than the exposure it prevents.
 */
object DatabaseEncryption {

    private const val TAG = "LucentDbCrypto"
    const val DB_NAME = "lucent.db"

    /** SQLite's own file header. Its presence is exactly what "this database is not encrypted" means. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Marker left behind when a database could not be decrypted, so Settings can say so. */
    private const val LOCKED_MARKER = "keys/db-locked.txt"

    @Volatile private var librariesLoaded = false

    /**
     * A database that was moved aside because its key was gone, if that has ever happened. Surfaced
     * in Settings → Data as a banner, because "your notes are missing" needs an explanation and a
     * way forward, not silence.
     */
    fun lockedNotice(context: Context): String? {
        val marker = File(context.applicationContext.filesDir, LOCKED_MARKER)
        return if (marker.exists()) marker.readText().ifBlank { null } else null
    }

    /** Called once the user has restored a backup, or dismissed the notice. */
    fun clearLockedNotice(context: Context) {
        File(context.applicationContext.filesDir, LOCKED_MARKER).delete()
    }

    private fun loadLibraries() {
        if (librariesLoaded) return
        synchronized(this) {
            if (librariesLoaded) return
            System.loadLibrary("sqlcipher")
            librariesLoaded = true
        }
    }

    /**
     * A no-op corruption handler.
     *
     * Every direct SQLCipher open in this file passes it, for the reason in the class comment: the
     * default handler **deletes the database**. Here, a failed open means "we could not decrypt
     * this", and the only correct response to that is to leave the file exactly where it is.
     */
    private val neverDelete = DatabaseErrorHandler { /* deliberately does nothing */ }

    private fun isPlaintextSqlite(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_MAGIC.size) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(SQLITE_MAGIC.size)
                if (input.read(head) != head.size) return false
                head.contentEquals(SQLITE_MAGIC)
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Prepare the database for Room and hand back the passphrase to open it with.
     *
     * Runs before anything touches the database — see `MainActivity`, and note that
     * `AppDatabase.getInstance` calls it too, because a `BroadcastReceiver` can reach the database
     * without the Activity ever having started.
     *
     * Returns null when SQLCipher is unusable on this device (the native library won't load, which
     * would be extraordinary but is not worth crashing over). The caller then opens the database
     * unencrypted, which is exactly what it does today — a strictly-no-worse outcome.
     */
    fun ensureReady(context: Context): String? {
        val appContext = context.applicationContext
        val dbFile = appContext.getDatabasePath(DB_NAME)

        val passphrase = try {
            loadLibraries()
            DataKeys.databasePassphrase(appContext)
        } catch (t: Throwable) {
            Log.e(TAG, "SQLCipher unavailable; the database will stay unencrypted", t)
            return null
        }

        // Fresh install: nothing to migrate. Room will create the file already encrypted.
        if (!dbFile.exists()) return passphrase

        if (isPlaintextSqlite(dbFile)) {
            val migrated = migrateToEncrypted(appContext, dbFile, passphrase)
            if (!migrated) {
                // The original is untouched and still plaintext. Run unencrypted this launch rather
                // than refusing to start, and try again next time.
                Log.e(TAG, "Could not encrypt the database; continuing unencrypted")
                return null
            }
        }

        // The file should now be encrypted with our key. Prove it *here*, where a failure is
        // survivable, rather than letting Room discover it somewhere a failure is destructive.
        if (canOpen(dbFile, passphrase)) return passphrase

        setAside(appContext, dbFile)
        return passphrase
    }

    /** Can this passphrase actually open the file? The whole point of this class in one function. */
    private fun canOpen(dbFile: File, passphrase: String): Boolean = try {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READONLY,
            neverDelete,
            null
        ).use { it.version >= 0 }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "The database could not be decrypted with the stored key", t)
        false
    }

    /**
     * Move an undecryptable database out of the way so the app can start.
     *
     * **Moved, not deleted.** The user's data is still on that disk, and the fact that we cannot read
     * it does not make it ours to throw away. Room then creates a fresh, empty, encrypted database
     * and the app opens — into Settings, where the notice tells them what happened and how to restore.
     * The alternative is a crash on launch, which leaves them with no route to the Import button and
     * no idea why.
     */
    private fun setAside(context: Context, dbFile: File) {
        val stamp = System.currentTimeMillis()
        val aside = File(dbFile.parentFile, "$DB_NAME.locked-$stamp")
        dbFile.renameTo(aside)
        // The journal files belong to the database we just moved; leaving them behind would let
        // SQLite try to replay them into the *new* one.
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()

        val marker = File(context.filesDir, LOCKED_MARKER)
        marker.parentFile?.mkdirs()
        marker.writeText(
            "Your notes database could not be decrypted on this launch, so it was set aside as " +
                "\"${aside.name}\" and a new empty one was created. Nothing has been deleted. " +
                "Import your most recent backup to restore your notes and tasks."
        )
        Log.e(TAG, "Database set aside as ${aside.name}")
    }

    /**
     * Convert an existing plaintext database to an encrypted one.
     *
     * The recipe is SQLCipher's own: open the plaintext file (an empty key means "no encryption"),
     * `ATTACH` a new encrypted file, and `sqlcipher_export` the contents across.
     *
     * Two things here are easy to get wrong and fatal if you do:
     *
     * **`user_version` is not copied by `sqlcipher_export`.** That's the field Room stores its schema
     * version in. Miss it and the new database claims to be version 0, Room concludes it needs to
     * migrate from the beginning of time, finds no path, and — with `fallbackToDestructiveMigration`
     * on — *drops every table*. So it is copied across explicitly.
     *
     * **The old `-wal` and `-shm` files must go.** They are the plaintext database's journal. Leave
     * them next to the new encrypted file and SQLite will try to replay them into it, which does not
     * end well. They're checkpointed into the main file first so nothing in them is lost, then
     * deleted.
     *
     * @return true if the database is now encrypted; false if it was left exactly as it was found.
     */
    private fun migrateToEncrypted(context: Context, dbFile: File, passphrase: String): Boolean {
        val encrypted = File(dbFile.parentFile, "$DB_NAME.encrypting")
        val original = File(dbFile.parentFile, "$DB_NAME.pre-encrypt")
        encrypted.delete()
        original.delete()

        val wal = File(dbFile.absolutePath + "-wal")
        val shm = File(dbFile.absolutePath + "-shm")

        try {
            // An empty passphrase opens the file with no encryption — SQLCipher's documented way of
            // reading a plaintext database so it can be exported.
            SQLiteDatabase.openOrCreateDatabase(dbFile, "", null, neverDelete).use { plain ->
                val schemaVersion = plain.version

                // Fold any write-ahead log back into the main file before we copy it, so an edit made
                // seconds before this upgrade isn't the one thing that doesn't survive it.
                try {
                    plain.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                } catch (t: Throwable) {
                    Log.w(TAG, "WAL checkpoint failed; continuing", t)
                }

                plain.rawExecSQL("ATTACH DATABASE ? AS encrypted KEY ?", encrypted.absolutePath, passphrase)
                plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                plain.rawExecSQL("PRAGMA encrypted.user_version = $schemaVersion")
                plain.rawExecSQL("DETACH DATABASE encrypted")
            }

            // Verify the new file before betting the user's notes on it.
            if (!canOpen(encrypted, passphrase)) {
                throw IllegalStateException("The encrypted copy could not be opened with its own key")
            }

            // Swap. The original is renamed rather than deleted, so a failure at any point below is
            // reversible right up to the last line.
            if (!dbFile.renameTo(original)) {
                throw IllegalStateException("Could not move the plaintext database aside")
            }
            wal.delete()
            shm.delete()
            if (!encrypted.renameTo(dbFile)) {
                original.renameTo(dbFile) // put it back exactly as it was
                throw IllegalStateException("Could not move the encrypted database into place")
            }

            original.delete()
            Log.i(TAG, "Database encrypted")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Database encryption failed; the original has been left untouched", t)
            encrypted.delete()
            // If we got as far as renaming the original away, put it back.
            if (!dbFile.exists() && original.exists()) original.renameTo(dbFile)
            original.delete()
            return false
        }
    }
}
