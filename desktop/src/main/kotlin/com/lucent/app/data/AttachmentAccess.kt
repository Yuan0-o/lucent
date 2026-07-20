package com.lucent.app.data

import android.content.Context
import java.io.File
import java.io.OutputStream

/**
 * Desktop twin of the Android AttachmentAccess: the one gate through which an encrypted attachment
 * becomes a plaintext file another program can read.
 *
 * Android materializes into the cache and hands out a FileProvider `content://` URI. Desktop
 * materializes into the same kind of cache (`cache/attachment-preview/<id>/<name>`) and either
 * returns the [File] for in-app rendering or opens it with the system's default application
 * ([openExternally]) — the Windows equivalent of "view with another app". [clearPreviewCache]
 * wipes the plaintext copies; "save to device" streams through [writeTo] and never touches the
 * cache, exactly like the original.
 */
object AttachmentAccess {

    private const val PREVIEW_DIR = "attachment-preview"
    private const val COPY_BUFFER = 64 * 1024

    private fun previewDir(context: Context): File =
        File(context.applicationContext.cacheDir, PREVIEW_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Write the decrypted copy into the preview cache and return the [File], or null on failure.
     * Runs I/O; call from a background thread.
     */
    fun materialize(context: Context, att: Attachment): File? {
        val dir = File(previewDir(context), safeFolder(att)).apply { if (!exists()) mkdirs() }
        val dest = File(dir, safeName(att.name))
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
     * Decrypt [att] to the preview cache and open it with the OS default application. Returns false
     * when the attachment can't be materialized or the desktop has no opener.
     */
    fun openExternally(context: Context, att: Attachment): Boolean {
        val file = materialize(context, att) ?: return false
        return try {
            if (!java.awt.Desktop.isDesktopSupported()) return false
            java.awt.Desktop.getDesktop().open(file)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Stream the decrypted bytes of [att] into [out] (a destination the user picked with the save
     * dialog). Closes [out]. Returns true on success.
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
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Delete every plaintext preview copy. Called at exit and from the privacy settings. */
    fun clearPreviewCache(context: Context) {
        previewDir(context).deleteRecursively()
    }

    // A stable per-attachment folder so two files sharing a display name never collide.
    private fun safeFolder(att: Attachment): String {
        val key = att.data.ifBlank { att.name }
        return key.filter { it.isLetterOrDigit() }.take(24).ifBlank { "att" }
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "attachment" }.take(120)
    }
}
