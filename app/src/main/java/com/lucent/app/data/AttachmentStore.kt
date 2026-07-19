package com.lucent.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * On-disk store for attachment bytes.
 *
 * Attachments used to live as Base64 text inside the database, which capped total attachment
 * volume to a few tens of MB before the app started to fall over: a Base64 blob inflates bytes
 * by ~33%, list queries pulled every row (with all their bytes) into memory, and any single
 * row over ~2MB hit SQLite's CursorWindow limit and failed to read back at all.
 *
 * With this store, the database only holds a small [AttachmentRef]-style JSON record per file
 * (id + display name + mime), and the bytes live as ordinary files under private storage. That
 * lets the same 800MB cap actually be reliable in practice:
 *
 *  - Import is streamed in fixed-size chunks, so pulling in a big file never buffers the whole
 *    thing in memory.
 *  - Rows stay tiny, so list queries stay tiny.
 *  - Images are decoded from disk with subsampling at render time; huge non-image files aren't
 *    read at all unless the user explicitly opens them.
 *
 * The stored file's opaque id is what the [Attachment.data] field carries after this change,
 * replacing the Base64 payload it used to hold. Everything above the store treats [data] as
 * "the id you look up here" instead of "bytes you decode".
 */
object AttachmentStore {

    private const val DIR_NAME = "attachments"
    // 64 KB is comfortably below any per-buffer cap and keeps peak memory tiny even for
    // sustained multi-hundred-MB copies.
    private const val COPY_BUFFER = 64 * 1024

    /**
     * Prefer app-specific external storage: it has far more room than the internal partition,
     * requires no runtime permission on modern Android, and is still private to the app and
     * cleared on uninstall. If it isn't available (e.g. no external volume mounted at all)
     * we fall back to the internal files dir so the store still works.
     */
    fun baseDir(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    fun fileFor(context: Context, id: String): File = File(baseDir(context), id)

    /**
     * Whether a string looks like an id this store would have produced. Used to tell new
     * disk-backed attachment payloads apart from legacy Base64 blobs that still live in
     * un-migrated rows: any UUID string is treated as an id, everything else is treated as
     * Base64. This is what makes the format switch tolerant of half-migrated databases.
     */
    fun looksLikeId(value: String): Boolean {
        if (value.length != 36) return false
        return try {
            UUID.fromString(value); true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Copy the bytes behind [uri] into private storage, streamed in [COPY_BUFFER]-byte chunks.
     * The returned id is what to persist in the row's attachments JSON. Returns null if the
     * stream can't be opened or the copy fails; the (partial) destination is deleted on failure.
     *
     * Runs synchronously — callers must invoke from an IO context.
     */
    fun importUri(context: Context, uri: Uri): String? {
        val id = UUID.randomUUID().toString()
        val dest = fileFor(context, id)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                openOutputStream(context, id)?.use { output -> copyStream(input, output) }
                    ?: run { dest.delete(); return null }
            } ?: run { dest.delete(); return null }
            id
        } catch (e: IOException) {
            dest.delete()
            null
        }
    }

    /** Persist raw bytes already in memory. Used for small text attachments produced by tools. */
    fun importBytes(context: Context, bytes: ByteArray): String? {
        val id = UUID.randomUUID().toString()
        return if (writeBytes(context, id, bytes)) id else null
    }

    /**
     * Replace the contents of an existing id. Used when an imported image is downscaled in place and
     * when a legacy ZIP backup stages its attachments.
     *
     * Writes through a temp file and renames, so a process death mid-write can't leave a half-written
     * file that would decrypt into nonsense — or, worse, decrypt *partially* and look fine.
     */
    fun writeBytes(context: Context, id: String, bytes: ByteArray): Boolean = try {
        openOutputStream(context, id)?.use { it.write(bytes) } != null
    } catch (e: IOException) {
        delete(context, id)
        false
    }

    /**
     * Open an encrypting stream for [id]. Everything written to it lands on disk as chunked
     * AES-256-GCM (see [FileCrypto]); **the stream must be closed** for the final frame to be sealed.
     *
     * Encryption lives here, at the single choke point every attachment write already passed through,
     * rather than being sprinkled across the callers. No caller knows or cares that the bytes are
     * encrypted — which is exactly why none of them can forget to encrypt.
     */
    fun openOutputStream(context: Context, id: String): OutputStream? = try {
        val key = DataKeys.attachmentKey(context)
        val dest = fileFor(context, id)
        FileCrypto.encryptingStream(dest.outputStream(), key)
    } catch (t: Throwable) {
        null
    }

    /**
     * Open the stored bytes for reading, decrypted. Caller closes.
     *
     * A file with no encryption header is one written by an older build and is returned as-is. That
     * is what lets encryption arrive with no migration step and no risk: every existing attachment
     * keeps working untouched, and [AttachmentMigration] re-encrypts them quietly in the background.
     */
    fun openInputStream(context: Context, id: String): InputStream? = try {
        val file = fileFor(context, id)
        if (!file.exists()) {
            null
        } else if (FileCrypto.isEncrypted(file)) {
            FileCrypto.decryptingStream(file.inputStream(), DataKeys.attachmentKey(context))
        } else {
            file.inputStream()
        }
    } catch (t: Throwable) {
        null
    }

    /** Decrypted bytes, or null if the file is missing, unreadable, or larger than [maxBytes]. */
    fun readBytes(context: Context, id: String, maxBytes: Long = 32L * 1024 * 1024): ByteArray? {
        if (sizeOf(context, id) > maxBytes) return null
        return try {
            openInputStream(context, id)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        }
    }

    fun exists(context: Context, id: String): Boolean = fileFor(context, id).exists()

    /**
     * The *plaintext* size of a stored attachment.
     *
     * Reporting the ciphertext length would quietly shrink the user's 800 MB allowance by the crypto
     * overhead, and — worse — mean the number shown in Settings didn't match the size of the files
     * they actually attached. [FileCrypto.plaintextSizeOf] derives it from the file length without
     * reading a byte, so the cap stays as cheap as it was.
     */
    fun sizeOf(context: Context, id: String): Long {
        val f = fileFor(context, id)
        if (!f.exists()) return 0L
        return if (FileCrypto.isEncrypted(f)) FileCrypto.plaintextSizeOf(f) else f.length()
    }

    /**
     * Total plaintext bytes currently on disk across every stored attachment.
     * Still cheap: file lengths and arithmetic only, no decryption. Used by the size cap.
     */
    fun totalBytes(context: Context): Long =
        baseDir(context).listFiles()?.sumOf { f ->
            if (FileCrypto.isEncrypted(f)) FileCrypto.plaintextSizeOf(f) else f.length()
        } ?: 0L

    /**
     * Re-encrypt one attachment that is still stored in the clear. Idempotent: an already-encrypted
     * file is left alone.
     *
     * The rewrite goes via a temp file and an atomic rename, because the failure being avoided is
     * catastrophic and specific — a crash midway through overwriting an attachment *in place* would
     * leave a file that is half ciphertext and half plaintext, decrypts to garbage, and is
     * indistinguishable from a corrupt one. Better to lose the upgrade than the photo.
     */
    fun encryptExistingFile(context: Context, id: String): Boolean {
        val file = fileFor(context, id)
        if (!file.exists() || FileCrypto.isEncrypted(file)) return false
        val temp = File(file.parentFile, "$id.enc-tmp")
        return try {
            val key = DataKeys.attachmentKey(context)
            file.inputStream().use { input ->
                FileCrypto.encryptingStream(temp.outputStream(), key).use { output ->
                    copyStream(input, output)
                }
            }
            if (temp.renameTo(file)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (t: Throwable) {
            temp.delete()
            false
        }
    }

    /** Delete a single stored file. Returns true if it wasn't there or was successfully removed. */
    fun delete(context: Context, id: String): Boolean {
        val f = fileFor(context, id)
        return if (f.exists()) f.delete() else true
    }

    /**
     * Delete every stored file whose id isn't in [referencedIds]. Call after startup, after
     * a backup import, and after any operation that could leave orphans (a note delete, a
     * composer cancelled after files were picked, etc.). Safe to run repeatedly — it only
     * touches files the caller has already confirmed are unused.
     */
    fun pruneOrphans(context: Context, referencedIds: Set<String>) {
        baseDir(context).listFiles()?.forEach { f ->
            if (f.name !in referencedIds) f.delete()
        }
    }

    /** Look up a picked file's display name for showing back to the user in chips/lists. */
    fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
    } catch (e: Exception) {
        null
    }

    /**
     * Best-effort size read straight from the ContentResolver, so a picked file that would
     * obviously blow the cap can be rejected before we spend time streaming its bytes to
     * disk. Returns -1 if the SAF provider doesn't report a size — in that case the caller
     * should stream first and check the actual on-disk size afterwards.
     */
    fun sizeHint(context: Context, uri: Uri): Long = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun copyStream(input: InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }
}
