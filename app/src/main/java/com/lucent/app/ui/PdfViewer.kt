package com.lucent.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.Attachment
import com.lucent.app.data.AttachmentAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A scrollable, in-app PDF reader — so a PDF attachment can be *read inside Lucent* instead of only
 * being handed off to another app.
 *
 * It leans on [android.graphics.pdf.PdfRenderer], part of the platform since API 21, so there's no
 * third-party PDF dependency to carry: the decrypted copy from [AttachmentAccess.materialize] is
 * opened read-only, every page is rasterised to a bitmap off the main thread, and the pages are laid
 * out top-to-bottom in a [LazyColumn] the user scrolls. Pages render at roughly twice their point
 * size for crispness, capped so a large document can't allocate an unreasonable bitmap.
 *
 * Word/PowerPoint documents are deliberately *not* rendered here: faithfully laying out `.docx`/`.pptx`
 * needs a heavyweight engine (Apache POI and a renderer) that would bloat the app for a rare case, so
 * those keep the "Open with" hand-off. PDF is the format worth rendering inline because the platform
 * already can. See change.md for the full rationale.
 */
@Composable
fun PdfViewer(att: Attachment) {
    val context = LocalContext.current
    var pages by remember(att.data) { mutableStateOf<List<Bitmap>?>(null) }
    var failed by remember(att.data) { mutableStateOf(false) }

    LaunchedEffect(att.data) {
        val rendered = withContext(Dispatchers.IO) {
            val file = AttachmentAccess.materialize(context, att) ?: return@withContext null
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor)
                val out = ArrayList<Bitmap>(renderer.pageCount)
                // Cap the number of pages eagerly rasterised so a giant PDF doesn't hold hundreds of
                // full-page bitmaps at once; beyond this the user can still open it externally.
                val pageLimit = minOf(renderer.pageCount, MAX_PAGES)
                for (i in 0 until pageLimit) {
                    renderer.openPage(i).use { page ->
                        // ~2x the page's point size (72 dpi → ~144 dpi), each dimension capped.
                        val scale = 2f
                        val width = (page.width * scale).toInt().coerceIn(1, MAX_DIM)
                        val height = (page.height * scale).toInt().coerceIn(1, MAX_DIM)
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // PDFs assume white paper; without this, transparent areas render black.
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(bmp)
                    }
                }
                out
            } catch (t: Throwable) {
                null
            } finally {
                try { renderer?.close() } catch (_: Throwable) {}
                try { descriptor?.close() } catch (_: Throwable) {}
            }
        }
        if (rendered != null) pages = rendered else failed = true
    }

    when {
        pages != null -> {
            val bitmaps = pages!!
            if (bitmaps.isEmpty()) {
                Text("This PDF has no pages to show.", color = Color.White)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(bitmaps) { index, bmp ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Page ${index + 1} of ${bitmaps.size}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
        failed -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text("Couldn't render this PDF. Try \"Open with\" instead.", color = Color.White)
        }
        else -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

private const val MAX_PAGES = 60
private const val MAX_DIM = 2200
