package com.lucent.app.data

import android.content.Context

/**
 * One place that owns the attachment size rule.
 *
 * ### The rule (changed)
 *
 * The limit is now **per file**, not per app. A *single* attachment may be at most
 * [MAX_SINGLE_BYTES] (600 MB); there is deliberately **no cap on the combined volume** of every
 * attachment across all notes and tasks. Attachment bytes live on disk in [AttachmentStore]
 * (streamed in, never buffered whole in memory), so the total is bounded only by the device's own
 * free space — which is the honest limit to enforce. Capping one enormous file keeps a single pick
 * from exhausting storage or memory in one go; capping the *sum* only punished people for keeping a
 * lot of small files, which was never the actual risk.
 *
 * The helpers that measured the running total ([totalStored], [sizeOfList]) are kept because they're
 * cheap (file lengths only) and a future "storage used" readout may want them, but nothing in the
 * size check consults the total any more.
 */
object AttachmentLimits {

    /** Hard ceiling on the size of any *one* attached file. */
    const val MAX_SINGLE_BYTES: Long = 600L * 1024 * 1024 // 600 MB

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024 * 1024)
        // Model files brought a new order of magnitude into this dialog: a 16 GB GGUF read as
        // "16384 MB" is technically true and practically unreadable, so gigabyte-scale sizes get
        // their own tier. One decimal keeps "1.5 GB" honest without pretending to precision.
        if (mb >= 1024) return String.format("%.1f GB", mb / 1024)
        return if (mb >= 10) "${mb.toInt()} MB"
        else String.format("%.1f MB", mb)
    }

    /**
     * Approximate size of a single attachment, whichever storage form it is in. Disk-backed
     * attachments report their `File.length()`; legacy Base64 attachments approximate the
     * decoded size from string length. Never touches the bytes.
     */
    fun sizeOf(context: Context, att: Attachment): Long {
        return if (AttachmentStore.looksLikeId(att.data)) {
            AttachmentStore.sizeOf(context, att.data)
        } else {
            Attachments.estimateDecodedBase64Size(att.data)
        }
    }

    fun sizeOfList(context: Context, list: List<Attachment>): Long =
        list.sumOf { sizeOf(context, it) }

    /**
     * Total size currently held by everything already saved. No longer used by the size check (the
     * limit is per file now), but kept for any diagnostics/"storage used" readout that wants it —
     * it's still cheap: on-disk file lengths plus an approximation for any un-migrated Base64 rows.
     */
    fun totalStored(context: Context, notes: List<Note>, tasks: List<Task>): Long {
        var total = AttachmentStore.totalBytes(context)
        val addLegacy: (String) -> Unit = { json ->
            Attachments.parse(json).forEach { att ->
                if (!AttachmentStore.looksLikeId(att.data)) {
                    total += Attachments.estimateDecodedBase64Size(att.data)
                }
            }
        }
        notes.forEach { addLegacy(it.attachments) }
        tasks.forEach { addLegacy(it.attachments) }
        return total
    }

    data class Check(val allowed: Boolean, val message: String)

    /**
     * Decide whether a single file of [incoming] bytes may be added. The only constraint is the
     * per-file ceiling; the combined volume of everything else is irrelevant.
     */
    fun checkSingle(incoming: Long): Check {
        return if (incoming <= MAX_SINGLE_BYTES) {
            Check(true, "")
        } else {
            Check(
                false,
                com.lucent.app.i18n.S.attachmentTooLarge(formatBytes(incoming), formatBytes(MAX_SINGLE_BYTES))
            )
        }
    }
}
