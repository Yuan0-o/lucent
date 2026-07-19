package com.lucent.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * Bridges Lucent's **encrypted, private** attachment storage to the rest of Android, which speaks in
 * `content://` URIs and expects plaintext.
 *
 * ### The problem this solves
 *
 * Attachments are stored two ways that both make them impossible to hand straight to another app:
 * they live in the app's private directory (no other app can read the path), and they're sealed with
 * chunked AES-256-GCM (the bytes on disk are ciphertext). So "open this PDF in a reader", "play this
 * video", "save this file to Downloads", and "share this file" all need the same missing step: a
 * *decrypted* copy at a location a `FileProvider` can vend a URI for.
 *
 * ### How it stays safe
 *
 * The decrypted copy is written into a single locked-down cache subdirectory ([PREVIEW_DIR]) that
 * the manifest's `FileProvider` is scoped to and nothing else can reach. When Lucent then grants a
 * viewer a URI, it grants read permission to that *one* app for that *one* file, and the grant is
 * revocable and doesn't outlive the task. The originals and the key files are never exposed — the
 * provider is configured so it literally cannot address them.
 *
 * The preview cache is transient by nature (it's under `cacheDir`, which the OS may clear), and
 * [clearPreviewCache] wipes it on demand. The plaintext copy only exists for as long as a preview or
 * a share needs it.
 *
 * ### Downloads don't go through here
 *
 * "Save to device" ([writeTo]) streams the decrypted bytes straight into a destination the user
 * picked through the system file picker (Storage Access Framework), so the plaintext lands only
 * where the user chose to put it and never touches the preview cache at all.
 */
object AttachmentAccess {

    private const val PREVIEW_DIR = "attachment-preview"
    private const val COPY_BUFFER = 64 * 1024

    private fun previewDir(context: Context): File =
        File(context.applicationContext.cacheDir, PREVIEW_DIR).apply { if (!exists()) mkdirs() }

    /** The FileProvider authority, kept in step with the manifest's `${applicationId}.fileprovider`. */
    private fun authority(context: Context): String =
        "${context.applicationContext.packageName}.fileprovider"

    /**
     * Decrypt [att] into the preview cache under its real display name and return a `content://` URI
     * a viewer or the share sheet can read. Returns null if the attachment is missing or can't be
     * decrypted.
     *
     * The file is named by the attachment's display name (sanitised) so the receiving app shows the
     * user a sensible title and extension — a video opens as "clip.mp4", not a UUID. A stable
     * per-attachment subfolder keeps two files that happen to share a name from colliding.
     *
     * Runs I/O; call from a background thread.
     */
    fun contentUri(context: Context, att: Attachment): Uri? {
        val plaintext = materialize(context, att) ?: return null
        return try {
            FileProvider.getUriForFile(context.applicationContext, authority(context), plaintext)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Write the decrypted copy into the preview cache and return the [File], or null on failure.
     * Exposed for callers that need the file itself (e.g. an in-app video player pointed at a URI).
     */
    fun materialize(context: Context, att: Attachment): File? {
        val dir = File(previewDir(context), safeFolder(att)).apply { if (!exists()) mkdirs() }
        val dest = File(dir, safeName(att.name))
        // Prefer streaming (large files never sit in memory). A legacy row that hasn't migrated to
        // disk yet has no stream — fall back to the in-memory bytes path so it can still be opened;
        // startup migration will move it to disk and the stream path takes over next time.
        val stream = Attachments.openStream(context, att)
        return try {
            if (stream != null) {
                stream.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(COPY_BUFFER)
                        while (true) {
                            val r = input.read(buf); if (r == -1) break; output.write(buf, 0, r)
                        }
                        output.flush()
                    }
                }
            } else {
                val bytes = Attachments.readBytes(context, att, maxBytes = Long.MAX_VALUE) ?: return null
                dest.outputStream().use { it.write(bytes); it.flush() }
            }
            dest
        } catch (t: Throwable) {
            dest.delete()
            null
        }
    }

    /**
     * Stream the decrypted bytes of [att] into [out] (a destination chosen through the Storage Access
     * Framework), for "Save to device". Streamed in fixed chunks so even a large video never has to
     * sit in memory. Returns true on success. Caller closes [out]? No — this closes it, because the
     * SAF descriptor must be closed for the write to be flushed to the picked location.
     */
    fun writeTo(context: Context, att: Attachment, out: OutputStream): Boolean {
        val stream = Attachments.openStream(context, att)
        return try {
            out.use { output ->
                if (stream != null) {
                    stream.use { input ->
                        val buf = ByteArray(COPY_BUFFER)
                        while (true) {
                            val r = input.read(buf); if (r == -1) break; output.write(buf, 0, r)
                        }
                    }
                } else {
                    val bytes = Attachments.readBytes(context, att, maxBytes = Long.MAX_VALUE) ?: return false
                    output.write(bytes)
                }
                output.flush()
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Remove every decrypted preview copy. Safe to call any time; the originals are untouched. */
    fun clearPreviewCache(context: Context) {
        try {
            previewDir(context).listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Throwable) {
        }
    }

    // A per-attachment folder derived from its data id (a UUID for disk-backed rows) so identical
    // display names don't overwrite each other. Legacy Base64 rows fall back to a hash of the name.
    private fun safeFolder(att: Attachment): String {
        val basis = if (AttachmentStore.looksLikeId(att.data)) att.data else att.name
        return basis.filter { it.isLetterOrDigit() || it == '-' }.take(40).ifBlank { "att" }
    }

    // Keep the real name+extension (so the OS picks the right viewer) but strip path separators and
    // anything that could escape the directory.
    private fun safeName(name: String): String {
        val cleaned = name.replace('\\', '_').replace('/', '_').trim()
        return cleaned.ifBlank { "file" }
    }
}
