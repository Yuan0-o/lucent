package com.lucent.app.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Desktop twin of the Android AttachmentStore: attachment bytes live once on disk, one file per
 * UUID id, encrypted through [FileCrypto] under the [DataKeys] attachment key. Rows carry the id.
 *
 * Differences from Android, both platform-shaped and nothing else:
 *  - Files live under the app data dir (`%APPDATA%\Lucent\attachments`) — desktop has no
 *    internal/external storage split.
 *  - Imports come from [java.io.File] (picked with a desktop file dialog) instead of a content
 *    Uri; [importFile] is the desktop counterpart of Android's `importUri`.
 * Everything else — ids, the lazy "unencrypted legacy file reads as-is" tolerance, the streamed
 * copies, the orphan sweep — matches the original line for line in behaviour.
 */
object AttachmentStore {

    private const val DIR_NAME = "attachments"
    private const val COPY_BUFFER = 64 * 1024

    fun baseDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, id: String): File = File(baseDir(context), id)

    /** Whether a string looks like an id this store would have produced (a UUID). */
    fun looksLikeId(value: String): Boolean {
        if (value.length != 36) return false
        return try {
            UUID.fromString(value); true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Copy [source]'s bytes into private storage, streamed and encrypted. The returned id is what
     * to persist in the row's attachments JSON; null when the copy fails. Desktop counterpart of
     * Android's `importUri` — call from an IO context.
     */
    fun importFile(context: Context, source: File): String? {
        val id = UUID.randomUUID().toString()
        val dest = fileFor(context, id)
        return try {
            source.inputStream().use { input ->
                openOutputStream(context, id)?.use { output -> copyStream(input, output) }
                    ?: run { dest.delete(); return null }
            }
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

    /** Replace the contents of an existing id (temp-file + rename inside FileCrypto's stream close). */
    fun writeBytes(context: Context, id: String, bytes: ByteArray): Boolean = try {
        openOutputStream(context, id)?.use { it.write(bytes) } != null
    } catch (e: IOException) {
        delete(context, id)
        false
    }

    /** An encrypting stream for [id] — must be closed for the final frame to be sealed. */
    fun openOutputStream(context: Context, id: String): OutputStream? = try {
        val key = DataKeys.attachmentKey(context)
        val dest = fileFor(context, id)
        FileCrypto.encryptingStream(dest.outputStream(), key)
    } catch (t: Throwable) {
        null
    }

    /**
     * Open the stored bytes for reading, decrypted. A file with no encryption header (written by a
     * build predating encryption, or staged plaintext) is returned as-is — same lazy tolerance as
     * Android, which is what lets [AttachmentMigration] re-encrypt quietly in the background.
     */
    fun openInputStream(context: Context, id: String): InputStream? = try {
        val file = fileFor(context, id)
        when {
            !file.exists() -> null
            FileCrypto.isEncrypted(file) ->
                FileCrypto.decryptingStream(file.inputStream(), DataKeys.attachmentKey(context))
            else -> file.inputStream()
        }
    } catch (t: Throwable) {
        null
    }

    /** Read the whole (decrypted) payload, refusing anything over [maxBytes]. */
    fun readBytes(context: Context, id: String, maxBytes: Long = 32L * 1024 * 1024): ByteArray? {
        val input = openInputStream(context, id) ?: return null
        return try {
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(COPY_BUFFER)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun exists(context: Context, id: String): Boolean = fileFor(context, id).exists()

    /** The plaintext size of the stored payload, derived cheaply (see FileCrypto.plaintextSizeOf). */
    fun sizeOf(context: Context, id: String): Long {
        val file = fileFor(context, id)
        if (!file.exists()) return 0L
        return if (FileCrypto.isEncrypted(file)) FileCrypto.plaintextSizeOf(file) else file.length()
    }

    /** Total plaintext bytes across every stored attachment (for the storage cap read-out). */
    fun totalBytes(context: Context): Long =
        baseDir(context).listFiles()?.sumOf { f ->
            if (FileCrypto.isEncrypted(f)) FileCrypto.plaintextSizeOf(f) else f.length()
        } ?: 0L

    /**
     * Encrypt one legacy plaintext file in place (temp file + rename). True when the file is
     * already encrypted or was converted; false only when conversion failed and the original is
     * left untouched. Used by the background sweep in [AttachmentMigration].
     */
    fun encryptExistingFile(context: Context, id: String): Boolean {
        val file = fileFor(context, id)
        if (!file.exists()) return false
        if (FileCrypto.isEncrypted(file)) return true
        return try {
            val key = DataKeys.attachmentKey(context)
            val temp = File(file.parentFile, "${file.name}.enc.tmp")
            file.inputStream().use { input ->
                FileCrypto.encryptingStream(temp.outputStream(), key).use { output ->
                    copyStream(input, output)
                }
            }
            if (temp.renameTo(file)) true
            else {
                // Windows refuses rename-over-existing; delete then rename, still leaving the
                // temp file behind (never the original) if the second step fails.
                if (file.delete() && temp.renameTo(file)) true else { temp.delete(); false }
            }
        } catch (t: Throwable) {
            File(file.parentFile, "${file.name}.enc.tmp").delete()
            false
        }
    }

    fun delete(context: Context, id: String): Boolean {
        val file = fileFor(context, id)
        return !file.exists() || file.delete()
    }

    /** Delete every stored file whose id is not in [referencedIds]. */
    fun pruneOrphans(context: Context, referencedIds: Set<String>) {
        baseDir(context).listFiles()?.forEach { file ->
            if (file.name !in referencedIds && looksLikeId(file.name)) file.delete()
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        return total
    }
}
