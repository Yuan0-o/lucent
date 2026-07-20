package com.lucent.app.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.Attachment
import com.lucent.app.data.AttachmentAccess
import com.lucent.app.data.AttachmentLimits
import com.lucent.app.data.AttachmentStore
import com.lucent.app.data.Attachments
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Desktop twin of the Android AttachmentUi: decode-and-thumbnail helpers plus the chip/card
 * composables shared by the notes, tasks, and assistant editors.
 *
 * Adaptations, all platform-shaped:
 *  - Decoding runs on Skia ([org.jetbrains.skia.Image]) instead of BitmapFactory. Skia decodes the
 *    same formats the Android path did (including WebP) and the subsampled-decode contract is
 *    preserved: a huge stored image renders as a bounded-size bitmap, never at full resolution.
 *  - `uriToAttachment`/`uriToChatImage` become [fileToAttachment]/[fileToChatImage], taking the
 *    [java.io.File] a desktop file dialog produces.
 *  - The save launcher wraps the AWT save dialog instead of the Storage Access Framework.
 * The composables themselves (chips, inline image cards, chip rows) mirror the Android layouts.
 */

private const val MAX_IMAGE_DIM = 1600

/**
 * Decode [bytes] into an [ImageBitmap] no larger than [maxDim] on its longest side, or null when
 * the bytes are not a decodable image. Downscaling happens on a Skia surface so the full-size
 * pixels live only for the duration of the draw.
 */
fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int = MAX_IMAGE_DIM): ImageBitmap? = try {
    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0) {
        null
    } else if (w <= maxDim && h <= maxDim) {
        image.toComposeImageBitmap()
    } else {
        val scale = maxDim.toFloat() / maxOf(w, h)
        val outW = maxOf(1, (w * scale).toInt())
        val outH = maxOf(1, (h * scale).toInt())
        val surface = Surface.makeRasterN32Premul(outW, outH)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(w.toFloat(), h.toFloat()),
            Rect.makeWH(outW.toFloat(), outH.toFloat()),
            null
        )
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
} catch (t: Throwable) {
    null
}

/** [decodeSampledBitmap] for a plaintext file on disk (used by the preview cache readers). */
fun decodeSampledBitmapFromFile(file: File, maxDim: Int = MAX_IMAGE_DIM): ImageBitmap? = try {
    decodeSampledBitmap(file.readBytes(), maxDim)
} catch (t: Throwable) {
    null
}

/** Re-encode [bytes] downscaled to [maxDim], preferring JPEG for photos and PNG otherwise. */
private fun downscaleImageBytes(bytes: ByteArray, mime: String, maxDim: Int = MAX_IMAGE_DIM): Pair<String, ByteArray> {
    return try {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val w = image.width
        val h = image.height
        if (w <= maxDim && h <= maxDim) return mime to bytes
        val scale = maxDim.toFloat() / maxOf(w, h)
        val outW = maxOf(1, (w * scale).toInt())
        val outH = maxOf(1, (h * scale).toInt())
        val surface = Surface.makeRasterN32Premul(outW, outH)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(w.toFloat(), h.toFloat()),
            Rect.makeWH(outW.toFloat(), outH.toFloat()),
            null
        )
        val snapshot = surface.makeImageSnapshot()
        val preferPng = mime == "image/png" || mime == "image/gif"
        val format = if (preferPng) EncodedImageFormat.PNG else EncodedImageFormat.JPEG
        val encoded = snapshot.encodeToData(format, 90)?.bytes
        if (encoded != null) {
            (if (preferPng) "image/png" else "image/jpeg") to encoded
        } else {
            mime to bytes
        }
    } catch (t: Throwable) {
        mime to bytes
    }
}

/** A rough mime guess from a file extension — the desktop stand-in for the resolver's type. */
fun mimeForFileName(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }
}

/**
 * Import a picked [file] into the attachment store and return the row-ready [Attachment], or null
 * when the copy fails or the file exceeds [AttachmentLimits]. Desktop counterpart of Android's
 * `uriToAttachment`; the size gate and disk-backed shape are identical. Call from an IO context.
 */
fun fileToAttachment(context: Context, file: File): Attachment? {
    if (!file.exists() || !file.isFile) return null
    if (file.length() > AttachmentLimits.MAX_SINGLE_BYTES) return null
    val id = AttachmentStore.importFile(context, file) ?: return null
    return Attachment(
        mime = mimeForFileName(file.name),
        data = id,
        name = file.name.ifBlank { "file" }
    )
}

/**
 * Import a picked image [file] for a CHAT message: downscaled, re-encoded, Base64'd, because chat
 * rows store their images inline. Returns (mime, base64, name) — same Triple as Android's
 * `uriToChatImage`. Call from an IO context.
 */
fun fileToChatImage(context: Context, file: File): Triple<String, String, String>? = try {
    if (!file.exists() || file.length() > AttachmentLimits.MAX_SINGLE_BYTES) {
        null
    } else {
        val mime = mimeForFileName(file.name)
        if (!mime.startsWith("image/")) {
            null
        } else {
            val (finalMime, bytes) = downscaleImageBytes(file.readBytes(), mime)
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            Triple(finalMime, base64, file.name.ifBlank { "image" })
        }
    }
} catch (t: Throwable) {
    null
}

/** Compact removable chips for attachments still pending on a not-yet-saved note/task. */
@Composable
fun PendingAttachmentChips(
    attachments: List<Attachment>,
    tint: Color,
    onRemove: (Attachment) -> Unit
) {
    if (attachments.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        attachments.forEach { att ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = tint)
                Text(att.name, color = tint, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
                IconButton(onClick = { onRemove(att) }, modifier = Modifier.padding(0.dp)) {
                    Icon(Icons.Default.Close, contentDescription = com.lucent.app.i18n.S.a11yRemoveNamed(att.name), tint = tint)
                }
            }
        }
    }
}

/**
 * Render a saved item's attachments: images inline (click to open full-size), video/audio/other
 * files as a labelled, clickable chip with a per-row download button — the Android card layout,
 * with the full-screen viewer opening on click exactly as there.
 */
@Composable
fun CardAttachments(
    attachments: List<Attachment>,
    onGradient: Color,
    onGradientMuted: Color
) {
    if (attachments.isEmpty()) return
    val context = android.content.DesktopContext
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val save = rememberSaveAttachmentLauncher()

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        attachments.forEach { att ->
            if (att.isImage) {
                val bitmap = remember(att.data) {
                    val bytes = Attachments.readBytes(context, att, maxBytes = 96L * 1024 * 1024)
                    if (bytes != null) decodeSampledBitmap(bytes) else null
                }
                if (bitmap != null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = att.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .pointerInput(att.data) {
                                    detectTapGestures(onTap = { viewing = att })
                                }
                        )
                    }
                } else {
                    AttachmentChipRow(att, onGradientMuted, onClick = { viewing = att }, onDownload = { save(att) })
                }
            } else {
                AttachmentChipRow(att, onGradientMuted, onClick = { viewing = att }, onDownload = { save(att) })
            }
        }
    }

    viewing?.let { att ->
        AttachmentViewerDialog(att = att, onDismiss = { viewing = null })
    }
}

@Composable
private fun AttachmentChipRow(att: Attachment, tint: Color, onClick: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .pointerInput(att.data) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(iconForAttachment(att), contentDescription = null, tint = tint)
        Text(att.name, color = tint, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
        IconButton(onClick = onDownload, modifier = Modifier.padding(0.dp)) {
            Icon(Icons.Default.Download, contentDescription = com.lucent.app.i18n.S.a11yDownloadNamed(att.name), tint = tint)
        }
    }
}

fun iconForAttachment(att: Attachment) = when {
    att.isImage -> Icons.Default.Image
    att.isVideo -> Icons.Default.Videocam
    att.isAudio -> Icons.Default.Audiotrack
    att.isPdf -> Icons.Default.PictureAsPdf
    else -> Icons.Default.Description
}

/**
 * A "save this attachment to disk" action: opens the system save dialog pre-filled with the
 * attachment's name and streams the decrypted bytes to the chosen location. Desktop counterpart of
 * the SAF CreateDocument launcher; like it, nothing is written when the user cancels.
 */
@Composable
fun rememberSaveAttachmentLauncher(): (Attachment) -> Unit {
    val context = android.content.DesktopContext
    return remember {
        { att ->
            Thread {
                try {
                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, com.lucent.app.i18n.S.actionSave, java.awt.FileDialog.SAVE)
                    dialog.file = att.name.ifBlank { "attachment" }
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val name = dialog.file
                    if (dir != null && name != null) {
                        val ok = AttachmentAccess.writeTo(context, att, File(dir, name).outputStream())
                        LucentToast.show(context, if (ok) com.lucent.app.i18n.S.savedToast else com.lucent.app.i18n.S.cantSaveFile)
                    }
                } catch (t: Throwable) {
                    LucentToast.show(context, com.lucent.app.i18n.S.cantSaveFile)
                }
            }.start()
        }
    }
}
