package com.lucent.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lucent.app.data.Attachment
import com.lucent.app.data.AttachmentAccess
import com.lucent.app.data.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Desktop twin of the Android AttachmentViewerDialog.
 *
 * Same job — a full-screen preview of one attachment over a dark scrim with an action row — and the
 * same shape for the two cases a desktop can render natively:
 *  - **Images** are zoom/pannable and gain an Edit action (crop / doodle / mosaic).
 *  - **PDFs** render page by page through Apache PDFBox into a scrollable column.
 *
 * The one deliberate platform divergence: **video and audio are opened in the system's default
 * player** rather than embedded. Android used a VideoView; Compose Desktop has no media widget
 * without a heavy native library, and Windows users already have capable players, so the honest
 * behaviour is [NonPreviewableInfo] plus a one-click "Open with". The Windows work report states
 * this. Every other attachment type behaves exactly as on Android.
 *
 * The action row is Save + Open-with for everything, plus Edit for images. Android's separate
 * "Share" has no desktop equivalent and folds into Open-with.
 */
@Composable
fun AttachmentViewerDialog(att: Attachment, onDismiss: () -> Unit) {
    val context = android.content.DesktopContext
    val save = rememberSaveAttachmentLauncher()
    var editing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))) {
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 96.dp, top = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    att.isImage -> ZoomableImage(att, reloadKey)
                    att.isPdf -> PdfViewer(att)
                    else -> NonPreviewableInfo(att)
                }
            }

            // Top bar: title + close.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(att.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = com.lucent.app.i18n.S.actionClose, tint = Color.White)
                }
            }

            // Bottom action row.
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 32.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (att.isImage) {
                    ViewerAction(Icons.Default.Edit, com.lucent.app.i18n.S.actionEdit) { editing = true }
                }
                ViewerAction(Icons.Default.Download, com.lucent.app.i18n.S.actionSave) { save(att) }
                ViewerAction(Icons.Default.Launch, com.lucent.app.i18n.S.openWith) {
                    Thread {
                        val ok = AttachmentAccess.openExternally(context, att)
                        if (!ok) LucentToast.show(context, com.lucent.app.i18n.S.cantOpenFile)
                    }.start()
                }
            }
        }
    }

    if (editing) {
        ImageEditorDialog(
            att = att,
            onDismiss = { editing = false },
            onSaved = { editing = false; reloadKey++ }
        )
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/** Scroll-wheel / pinch zoom and drag-pan image view. Decodes from the encrypted store off-thread. */
@Composable
private fun ZoomableImage(att: Attachment, reloadKey: Int = 0) {
    val context = android.content.DesktopContext
    var bitmap by remember(att.data, reloadKey) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(att.data, reloadKey) { mutableStateOf(false) }

    LaunchedEffect(att.data, reloadKey) {
        val bmp = withContext(Dispatchers.IO) {
            val bytes = Attachments.readBytes(context, att, maxBytes = 64L * 1024 * 1024)
            if (bytes != null) decodeSampledBitmap(bytes, maxDim = 2560) else null
        }
        if (bmp != null) bitmap = bmp else failed = true
    }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    when {
        bitmap != null -> Image(
            bitmap = bitmap!!,
            contentDescription = att.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                .transformable(transformState)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2f
                    })
                }
        )
        failed -> Text(com.lucent.app.i18n.S.cantLoadImage, color = Color.White)
        else -> CircularProgressIndicator(color = Color.White)
    }
}

/**
 * Render a PDF attachment page-by-page with Apache PDFBox and show the pages in a vertical scroll.
 * Pages are rasterized off the main thread at a readable DPI; a failure falls back to the
 * "open it elsewhere" info panel rather than a blank screen.
 */
@Composable
private fun PdfViewer(att: Attachment) {
    val context = android.content.DesktopContext
    var pages by remember(att.data) { mutableStateOf<List<ImageBitmap>?>(null) }
    var failed by remember(att.data) { mutableStateOf(false) }

    LaunchedEffect(att.data) {
        val rendered = withContext(Dispatchers.IO) {
            try {
                val bytes = Attachments.readBytes(context, att, maxBytes = 256L * 1024 * 1024) ?: return@withContext null
                org.apache.pdfbox.Loader.loadPDF(bytes).use { doc ->
                    val renderer = org.apache.pdfbox.rendering.PDFRenderer(doc)
                    val count = doc.numberOfPages.coerceAtMost(60) // guard a pathological page count
                    (0 until count).mapNotNull { i ->
                        val image = renderer.renderImageWithDPI(i, 120f)
                        val baos = ByteArrayOutputStream()
                        javax.imageio.ImageIO.write(image, "png", baos)
                        decodeSampledBitmap(baos.toByteArray(), maxDim = 2000)
                    }
                }
            } catch (t: Throwable) {
                null
            }
        }
        if (rendered != null && rendered.isNotEmpty()) pages = rendered else failed = true
    }

    when {
        pages != null -> Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            pages!!.forEach { page ->
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = page,
                    contentDescription = att.name,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        failed -> NonPreviewableInfo(att)
        else -> CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun NonPreviewableInfo(att: Attachment) {
    val context = android.content.DesktopContext
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(Icons.Default.Launch, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(att.name, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(com.lucent.app.i18n.S.noPreviewForType, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        // A media file lands here on desktop; make the system-player hand-off one obvious click.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .pointerInput(att.data) {
                    detectTapGestures(onTap = {
                        Thread {
                            val ok = AttachmentAccess.openExternally(context, att)
                            if (!ok) LucentToast.show(context, com.lucent.app.i18n.S.cantOpenFile)
                        }.start()
                    })
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Launch, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(com.lucent.app.i18n.S.openWith, color = Color.White, fontSize = 14.sp)
        }
    }
}
