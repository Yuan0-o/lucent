package com.lucent.app.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single attachment on a note or task.
 *
 * ### Storage model change
 * Historically [data] was a Base64-encoded copy of the raw bytes stored directly inside the
 * database. That capped total attachment volume to a few tens of MB before the app fell over.
 *
 * With disk-backed storage, [data] is now an *id* into [AttachmentStore] — an opaque UUID
 * naming a file under private storage. The database row keeps only these small ids, and the
 * bytes live on disk where they belong. This is what makes the app-wide 800MB cap actually
 * usable in practice.
 *
 * Legacy rows created before this change still have Base64 in [data]. Every consumer decides
 * which format it's holding by asking [AttachmentStore.looksLikeId]; the one-shot startup
 * migration ([AttachmentMigration]) rewrites all legacy rows to the disk-backed form in the
 * background.
 */
data class Attachment(
    val mime: String,
    val data: String,
    val name: String
) {
    val isImage: Boolean get() = mime.startsWith("image/")

    /** A video file Lucent can preview inline with the built-in player. */
    val isVideo: Boolean get() = mime.startsWith("video/")

    /** An audio file Lucent can play inline with the built-in player. */
    val isAudio: Boolean get() = mime.startsWith("audio/")

    /** A PDF — previewable through the system viewer rather than inline. */
    val isPdf: Boolean get() = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)

    /** Anything Lucent can show *inside* the app (image or video); everything else opens externally. */
    val isInlineViewable: Boolean get() = isImage || isVideo || isAudio
}

object Attachments {

    /** Parse the JSON-array string stored on a Note/Task into a list. Never throws. */
    fun parse(json: String?): List<Attachment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Attachment(
                    mime = o.optString("mime", "application/octet-stream"),
                    data = o.optString("data", ""),
                    name = o.optString("name", "file")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Serialize a list back into the JSON-array string form stored in the database. */
    fun serialize(list: List<Attachment>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("mime", it.mime)
                    .put("data", it.data)
                    .put("name", it.name)
            )
        }
        return arr.toString()
    }

    // ---- Byte access -------------------------------------------------------------------------
    //
    // Every read/write of the actual attachment bytes now goes through here. Callers pass a
    // context so we can reach the on-disk store; the two paths (new disk-backed rows and
    // legacy Base64 rows) are unified so existing UI code doesn't have to care which shape
    // it happens to be holding at any given moment.

    /**
     * The number of raw bytes this attachment holds. For disk-backed rows this is
     * `File.length()`, which is O(1) and doesn't touch the bytes at all. For legacy Base64
     * rows we approximate from string length so we never have to actually decode a huge blob
     * just to size it up.
     */
    fun byteSize(context: Context, att: Attachment): Long {
        return if (AttachmentStore.looksLikeId(att.data)) {
            AttachmentStore.sizeOf(context, att.data)
        } else {
            estimateDecodedBase64Size(att.data)
        }
    }

    /**
     * Read the attachment's bytes back into memory. Used for images (which get sampled down
     * before decoding to a bitmap) and for text attachments the assistant wants to inline.
     * Returns null if the attachment is missing, oversized for a full in-memory read, or the
     * disk read failed.
     *
     * The [maxBytes] guard is there so a huge non-image attachment can't be silently pulled
     * into memory just because someone called `readBytes()` on it — that would defeat the
     * whole point of moving to disk storage. Callers that need to stream a large file should
     * open a stream directly via [openStream] instead.
     */
    fun readBytes(context: Context, att: Attachment, maxBytes: Long = 32L * 1024 * 1024): ByteArray? {
        return if (AttachmentStore.looksLikeId(att.data)) {
            // Goes through the store, which decrypts. Reading the File directly — as this used to —
            // would now hand back ciphertext, and the caller would have no way to tell.
            AttachmentStore.readBytes(context, att.data, maxBytes)
        } else {
            val approx = estimateDecodedBase64Size(att.data)
            if (approx > maxBytes) return null
            try {
                android.util.Base64.decode(att.data, android.util.Base64.DEFAULT)
            } catch (t: Throwable) {
                null
            }
        }
    }

    /**
     * Open the attachment for streaming. Returns null for legacy Base64 rows (they aren't on
     * disk yet) — the migration runs on startup and any un-migrated blob will convert to
     * disk form there, so the next call succeeds.
     */
    fun openStream(context: Context, att: Attachment) =
        if (AttachmentStore.looksLikeId(att.data)) AttachmentStore.openInputStream(context, att.data) else null

    /**
     * Decode a text-like attachment back into a String, or null for images / binary files /
     * anything that doesn't decode as valid UTF-8 text. The size guard is deliberately much
     * lower here than for [readBytes] because a text attachment large enough to be useful to
     * the assistant fits in well under a megabyte.
     */
    fun decodeText(context: Context, att: Attachment): String? {
        if (att.isImage) return null
        val looksTextual = att.mime.startsWith("text/") ||
            att.mime == "application/json" ||
            att.mime == "application/xml" ||
            att.mime.endsWith("+json") ||
            att.mime.endsWith("+xml") ||
            att.mime == "application/octet-stream" // may still be plain text; verified below
        if (!looksTextual) return null
        val bytes = readBytes(context, att, maxBytes = 2L * 1024 * 1024) ?: return null
        // Reject data with NUL bytes: a strong signal it's binary, not text.
        if (bytes.any { it.toInt() == 0 }) return null
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Read the bytes and Base64-encode them for shipping through an API turn (used when the
     * assistant surfaces an image attachment to the vision model). Images are already
     * downscaled at import, so this stays small in practice.
     */
    fun readAsBase64(context: Context, att: Attachment, maxBytes: Long = 8L * 1024 * 1024): String? {
        val bytes = readBytes(context, att, maxBytes) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Build a plain-text attachment written to disk (used by the assistant, which can produce
     * text file contents but not upload binaries).
     */
    fun textAttachment(context: Context, name: String, content: String): Attachment? {
        val safeName = if (name.isBlank()) "note.txt" else name
        val id = AttachmentStore.importBytes(context, content.toByteArray(Charsets.UTF_8)) ?: return null
        return Attachment(mime = "text/plain", data = id, name = safeName)
    }

    /**
     * Upsert by file name (case-insensitive). If [attachment] replaces an older entry, the
     * older on-disk file is dropped so it doesn't linger as an orphan. Files added but never
     * saved (e.g. the user cancels the composer) are cleaned up by the startup orphan sweep.
     */
    fun upsert(context: Context, list: List<Attachment>, attachment: Attachment): List<Attachment> {
        val idx = list.indexOfFirst { it.name.equals(attachment.name, ignoreCase = true) }
        return if (idx >= 0) {
            val displaced = list[idx]
            if (AttachmentStore.looksLikeId(displaced.data) && displaced.data != attachment.data) {
                AttachmentStore.delete(context, displaced.data)
            }
            list.toMutableList().also { it[idx] = attachment }
        } else list + attachment
    }

    /**
     * Remove by file name (case-insensitive), deleting the on-disk file as we go so a big
     * attachment thrown away in the composer frees its space immediately.
     */
    fun removeByName(context: Context, list: List<Attachment>, name: String): List<Attachment> {
        val toRemove = list.filter { it.name.equals(name, ignoreCase = true) }
        toRemove.forEach { att ->
            if (AttachmentStore.looksLikeId(att.data)) AttachmentStore.delete(context, att.data)
        }
        return list.filterNot { it.name.equals(name, ignoreCase = true) }
    }

    /** Collect all disk-store ids across a JSON attachments blob (used for orphan sweeps). */
    fun idsFromJson(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return parse(json)
            .map { it.data }
            .filter { AttachmentStore.looksLikeId(it) }
            .toSet()
    }

    /** Approximate decoded-byte size of a Base64 string without actually decoding it. */
    fun estimateDecodedBase64Size(base64: String): Long {
        if (base64.isEmpty()) return 0
        val padding = when {
            base64.endsWith("==") -> 2
            base64.endsWith("=") -> 1
            else -> 0
        }
        return (base64.length.toLong() * 3 / 4) - padding
    }
}
