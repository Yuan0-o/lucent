package com.lucent.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.Attachment
import com.lucent.app.data.AttachmentStore
import com.lucent.app.data.Attachments
import java.io.File

/**
 * Longest-edge cap for stored images. Full-resolution phone photos (12MP+) inflate to tens of
 * MB in ARGB_8888 when decoded, which is what could exhaust memory when the app tries to
 * render several of them. We downscale on import so the stored copy is small and cheap to
 * decode; users who need pixel-perfect originals can attach to a note without downscaling by
 * picking through the file picker instead of a photo picker (irrelevant here — the picker
 * this code owns always downscales images).
 */
private const val MAX_IMAGE_DIM = 1600
// Below this size an image is small enough that re-encoding it would just cost quality.
private const val DOWNSCALE_BYTE_THRESHOLD = 400_000

/**
 * Two-pass decode of a byte array with subsampling so the resulting bitmap's longest edge is
 * at most ~[maxDim]. Catches `Throwable` because [OutOfMemoryError] is an `Error` (not an
 * `Exception`) and would otherwise slip past a plain `catch (e: Exception)` and crash.
 */
fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int = MAX_IMAGE_DIM): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (t: Throwable) {
        null
    }
}

/**
 * Same two-pass subsampled decode as [decodeSampledBitmap], but reading directly from a file
 * on disk so the whole encoded image never has to sit in a Kotlin `ByteArray`. This is what
 * lets huge stored images render safely: we bounds-decode once (no pixel data), pick a sample
 * factor, then decode a downscaled bitmap — never touching the full resolution pixels.
 */
fun decodeSampledBitmapFromFile(file: File, maxDim: Int = MAX_IMAGE_DIM): Bitmap? {
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (t: Throwable) {
        null
    }
}

private fun downscaleImageFileInPlace(file: File, mime: String, maxDim: Int = MAX_IMAGE_DIM): String {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return mime
        if (longest <= maxDim && file.length() <= DOWNSCALE_BYTE_THRESHOLD) return mime

        val decoded = decodeSampledBitmapFromFile(file, maxDim) ?: return mime
        val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else decoded

        val useJpeg = mime.equals("image/jpeg", ignoreCase = true) || mime.equals("image/jpg", ignoreCase = true)
        val outMime = if (useJpeg) "image/jpeg" else "image/png"
        file.outputStream().use { out ->
            if (useJpeg) scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            else scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        outMime
    } catch (t: Throwable) {
        mime
    }
}

/**
 * Read any picked file into an [Attachment] stored on disk.
 *
 * The picked bytes stream through a 64KB buffer straight into a private (encrypted) file, so a
 * hundreds-of-MB pick never inflates into a `ByteArray` in memory.
 *
 * **Images are stored at their original quality and size** — the bytes are copied verbatim, never
 * re-encoded or downscaled. Keeping the file exactly as the user attached it is a deliberate choice:
 * a notes app that silently re-compresses your photos is quietly degrading your data, and the render
 * paths already subsample images from disk at display time (see [decodeSampledBitmap]), so a large
 * original costs nothing to *show* — it only costs the disk space the user chose to spend. The old
 * import-time downscale traded that away for a smaller file nobody had asked to shrink.
 *
 * Returns null if the stream can't be opened; on failure the partial file is cleaned up.
 * Must be called from an IO context (streams block).
 */
fun uriToAttachment(context: Context, uri: Uri): Attachment? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = AttachmentStore.queryDisplayName(context, uri) ?: "file"
    val id = AttachmentStore.importUri(context, uri) ?: return null
    // No image downscaling: the stored copy is byte-for-byte the source file, original quality kept.
    return Attachment(mime = mime, data = id, name = name)
}

/**
 * Prepare a chat-message image attachment: stream to disk, downscale, then read the (small)
 * downscaled bytes back so they can be Base64-encoded into the ChatMessage. Chat attachments
 * live inside the message row for portability with backups; images always end up small after
 * downscaling so this remains cheap even when the source was a 500MB photo. Non-image files
 * aren't handled here — the assistant screen inlines their text into the message body instead.
 *
 * Returns (mime, base64) on success, or null.
 */
fun uriToChatImage(context: Context, uri: Uri): Triple<String, String, String>? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: return null
    if (!mime.startsWith("image/")) return null
    val name = AttachmentStore.queryDisplayName(context, uri) ?: "image"
    // Stream the source to a scratch file, downscale, read the small result back, then
    // delete the scratch. We deliberately do NOT put it in the AttachmentStore because it
    // isn't a persistent note/task attachment; leaving it there would show up as an orphan
    // to the sweep and be silently deleted.
    val scratch = File.createTempFile("chatimg_", null, context.cacheDir)
    return try {
        resolver.openInputStream(uri)?.use { input ->
            scratch.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf); if (r == -1) break; out.write(buf, 0, r)
                }
            }
        } ?: return null
        val finalMime = downscaleImageFileInPlace(scratch, mime)
        // At this point `scratch` holds the downscaled image (typically well under 1 MB),
        // so reading it back into memory to Base64-encode it is fine and unavoidable — the
        // chat message row stores its attachment inline.
        val base64 = android.util.Base64.encodeToString(scratch.readBytes(), android.util.Base64.NO_WRAP)
        Triple(finalMime, base64, name)
    } catch (t: Throwable) {
        null
    } finally {
        scratch.delete()
    }
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
                    Icon(Icons.Default.Close, contentDescription = "Remove ${att.name}", tint = tint)
                }
            }
        }
    }
}

/**
 * Render a saved item's attachments: images inline (tap to open full-screen), video/audio/other
 * files as a labelled, tappable chip. Tapping any attachment opens [AttachmentViewerDialog], which
 * previews images and video inside the app and offers Save / Share / Open-with for everything.
 *
 * Images are decoded directly from disk with subsampling so a huge stored image renders without ever
 * holding the full-resolution pixels in memory.
 */
@Composable
fun CardAttachments(
    attachments: List<Attachment>,
    onGradient: Color,
    onGradientMuted: Color
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    // Which attachment, if any, is open in the full-screen viewer.
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    // A clear per-attachment "download to device" affordance (feature: obvious download button). The
    // full-screen viewer has always had Save; this surfaces the same action right on the card row so
    // it's reachable in one tap without opening the file first.
    val save = rememberSaveAttachmentLauncher()

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        attachments.forEach { att ->
            if (att.isImage) {
                val bitmap = remember(att.data) {
                    // One path for both storage forms now: Attachments.readBytes goes through the
                    // store, which decrypts. Reading the File directly — as this used to — would
                    // hand BitmapFactory ciphertext and silently render nothing.
                    // Now that images are stored at original resolution, allow a larger in-memory
                    // read here so a big original still produces a (subsampled) thumbnail instead of
                    // silently falling back to a file chip. The decode itself stays subsampled.
                    val bytes = Attachments.readBytes(context, att, maxBytes = 96L * 1024 * 1024)
                    if (bytes != null) decodeSampledBitmap(bytes) else null
                }
                if (bitmap != null) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = att.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                // Tap the thumbnail to open it full-screen (zoomable), with the same
                                // Save / Share / Open-with / Edit actions as every other attachment.
                                .pointerInput(att.data) {
                                    detectTapGestures(onTap = { Haptics.tick(context); viewing = att })
                                }
                        )
                        // The top-right "download" button was removed here (task 2): tapping the
                        // thumbnail already opens the full-screen viewer, which has its own Save
                        // button, so this corner button was a redundant second way to do the same
                        // thing and is gone.
                    }
                } else {
                    AttachmentChipRow(att, onGradientMuted, onClick = { Haptics.tick(context); viewing = att }, onDownload = { Haptics.tick(context); save(att) })
                }
            } else {
                AttachmentChipRow(att, onGradient, onClick = { Haptics.tick(context); viewing = att }, onDownload = { Haptics.tick(context); save(att) })
            }
        }
    }

    viewing?.let { att ->
        AttachmentViewerDialog(att = att, onDismiss = { viewing = null })
    }
}

/** A tappable attachment row: type icon, name, an explicit download button, and an open hint. */
@Composable
private fun AttachmentChipRow(att: Attachment, tint: Color, onClick: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .pointerInput(att.data) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(iconForAttachment(att), contentDescription = null, tint = tint)
        Text(
            att.name,
            color = tint,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(start = 6.dp)
        )
        // Explicit download button, then a quiet "opens on tap" hint.
        IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Download ${att.name}",
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
        Icon(
            Icons.Default.Visibility,
            contentDescription = "Open ${att.name}",
            tint = tint.copy(alpha = 0.7f)
        )
    }
}

/** Pick a leading icon that tells the user what kind of file this is at a glance. */
private fun iconForAttachment(att: Attachment) = when {
    att.isVideo -> Icons.Default.Movie
    att.isAudio -> Icons.Default.MusicNote
    att.isPdf -> Icons.Default.PictureAsPdf
    att.isImage -> Icons.Default.Image
    else -> Icons.Default.InsertDriveFile
}
