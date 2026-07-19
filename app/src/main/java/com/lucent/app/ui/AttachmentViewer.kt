package com.lucent.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lucent.app.data.Attachment
import com.lucent.app.data.AttachmentAccess
import com.lucent.app.data.Attachments
import com.lucent.app.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything a user can do with a saved attachment beyond seeing it on the card: open it full-screen
 * inside Lucent (images and video), hand it to another app to view (PDFs, documents, anything),
 * **download** a decrypted copy to a location they pick, or share it out.
 *
 * All of it runs against the encrypted store through [AttachmentAccess], which produces the
 * decrypted, shareable copy on demand and keeps the originals sealed. Nothing here ever writes
 * plaintext anywhere the user didn't choose (a download) or anywhere another app can reach except
 * the one file, the one app, for the one action (a preview or a share).
 */

// ---------------------------------------------------------------------------------------
// Actions (open externally / share). Download is a Composable launcher, see below.
// ---------------------------------------------------------------------------------------

private fun toast(context: Context, msg: String) =
    LucentToast.show(context.applicationContext, msg)

/** Hand the attachment to whatever app the user picks to *view* it (ACTION_VIEW). */
fun openAttachmentExternally(context: Context, att: Attachment) {
    AppScope.io.launch {
        val uri = AttachmentAccess.contentUri(context, att)
        withContext(Dispatchers.Main) {
            if (uri == null) {
                toast(context, "Couldn't open this file")
                return@withContext
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, att.mime.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Open with"))
            } catch (t: Throwable) {
                toast(context, "No app can open this file")
            }
        }
    }
}

/** Share the attachment out through the system share sheet (ACTION_SEND, with the real bytes). */
fun shareAttachment(context: Context, att: Attachment) {
    AppScope.io.launch {
        val uri = AttachmentAccess.contentUri(context, att)
        withContext(Dispatchers.Main) {
            if (uri == null) {
                toast(context, "Couldn't share this file")
                return@withContext
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = att.mime.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Share file"))
            } catch (t: Throwable) {
                toast(context, "Couldn't share this file")
            }
        }
    }
}

/**
 * A remembered "Save to device" launcher. Call the returned lambda with an attachment to open the
 * system's Save-file dialog for it; the decrypted bytes are then streamed into the location the user
 * chose. Using the Storage Access Framework means the plaintext copy lands only where the user put
 * it (Downloads, Drive, wherever) — Lucent never writes it anywhere else.
 */
@Composable
fun rememberSaveAttachmentLauncher(): (Attachment) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Attachment?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val att = pending
        pending = null
        if (uri == null || att == null) return@rememberLauncherForActivityResult
        AppScope.io.launch {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.let { out ->
                    AttachmentAccess.writeTo(context, att, out)
                } ?: false
            } catch (t: Throwable) {
                false
            }
            withContext(Dispatchers.Main) {
                toast(context, if (ok) "Saved" else "Couldn't save this file")
            }
        }
    }
    return { att ->
        pending = att
        // Suggest the real filename; the picker lets the user change it and pick a folder.
        launcher.launch(att.name.ifBlank { "attachment" })
    }
}

// ---------------------------------------------------------------------------------------
// Full-screen viewer
// ---------------------------------------------------------------------------------------

/**
 * A full-screen preview of one attachment, over a dark scrim, with a Save / Share / Open-with action
 * row across the bottom. Images are pinch-to-zoom and pan; video and audio play with standard
 * transport controls; anything not inline-viewable shows its details and leans on the action row to
 * open it elsewhere. [onDismiss] closes it.
 */
@Composable
fun AttachmentViewerDialog(att: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val save = rememberSaveAttachmentLauncher()
    // Opening the in-app image editor over this viewer; bumping reloadKey after a save forces the
    // image to be re-decoded so the edit is visible immediately even though the attachment id is
    // unchanged (the editor writes back over the same stored bytes).
    var editing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // Whether the viewer's own furniture — the title bar and the Save/Share/Open-with row — is
    // showing. Tapping a video or audio track toggles it, which is what turns this dialog into an
    // actual full-screen player instead of a video wedged between two toolbars.
    var chromeVisible by remember { mutableStateOf(true) }
    val isMedia = att.isVideo || att.isAudio

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            // The preview surface fills the screen; the top bar and bottom actions float over it.
            // With the chrome hidden the reserved bands collapse to nothing, so the video really does
            // use the whole display rather than merely losing two toolbars it was already inset by.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        bottom = if (chromeVisible) 96.dp else 0.dp,
                        top = if (chromeVisible) 56.dp else 0.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    att.isImage -> ZoomableImage(att, reloadKey)
                    att.isVideo || att.isAudio -> InlineMediaPlayer(
                        att = att,
                        chromeVisible = chromeVisible,
                        onToggleChrome = { chromeVisible = !chromeVisible }
                    )
                    att.isPdf -> PdfViewer(att)
                    else -> NonPreviewableInfo(att)
                }
            }

            // Top bar: title + close. Hidden along with everything else while a video is playing
            // full-screen; a tap anywhere on the video brings it back.
            if (!isMedia || chromeVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    att.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            }

            // Bottom action row. Images gain an Edit action (crop/mosaic/doodle); everything keeps
            // Save / Share / Open-with.
            if (!isMedia || chromeVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (att.isImage) {
                    ViewerAction(Icons.Default.Edit, "Edit") { editing = true }
                }
                ViewerAction(Icons.Default.Download, "Save") { save(att) }
                ViewerAction(Icons.Default.Share, "Share") { shareAttachment(context, att) }
                ViewerAction(Icons.Default.Launch, "Open with") {
                    openAttachmentExternally(context, att)
                }
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
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { Haptics.tick(context); onClick() }) }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/** Pinch-to-zoom, double-tap-to-reset image view. Decodes from the encrypted store off the main thread. */
@Composable
private fun ZoomableImage(att: Attachment, reloadKey: Int = 0) {
    val context = LocalContext.current
    var bitmap by remember(att.data, reloadKey) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(att.data, reloadKey) { mutableStateOf(false) }

    LaunchedEffect(att.data, reloadKey) {
        val bmp = withContext(Dispatchers.IO) {
            // A larger cap than the card thumbnail: this is a full-screen view, so allow more detail.
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
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = att.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
                .transformable(transformState)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        // Snap between fit and 2x, recentring on reset.
                        if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2f
                    })
                }
        )
        failed -> Text("Couldn't load this image", color = Color.White)
        else -> CircularProgressIndicator(color = Color.White)
    }
}

/**
 * A real media player for video **and** audio attachments (task 1).
 *
 * ### What was wrong with the old one
 *
 * It handed a [VideoView] an `android.widget.MediaController` and hoped. `MediaController` is a
 * `PopupWindow` that anchors itself to a View, and this player lives inside a Compose `Dialog` —
 * i.e. its own window. A popup anchored into another window is exactly the case that arrangement
 * handles worst: the transport bar either never appeared or appeared somewhere unhelpful, which is
 * why there was no scrub bar to drag. Audio was worse still — a `VideoView` playing an audio file
 * draws nothing at all, so the "player" was a black rectangle with no controls, no position, and no
 * way to seek.
 *
 * ### What this does instead
 *
 * The transport is drawn in Compose, in the same window as everything else, so it simply cannot fail
 * to appear:
 *
 *  - **A real scrub bar.** A [Slider] bound to the player's position, draggable to seek. While the
 *    thumb is held the bar follows the finger rather than the playhead (otherwise the poll below
 *    fights the drag and the thumb jitters), and the seek is issued once, on release.
 *  - **Tap to hide.** One tap anywhere on the surface hides the controls *and* the viewer's own
 *    chrome for full-screen playback; another brings them back. While playing they also fade
 *    themselves out after a few seconds, the way every video player does.
 *  - **Position, duration, play/pause, replay**, and the playback-speed selector the old one had.
 *  - **Audio gets the same treatment**, with a music glyph and the file name standing in for the
 *    (empty) video surface, so an audio attachment is now a playable track rather than a void.
 *
 * [VideoView] is still the engine — it handles every format a phone records or downloads without a
 * media library dependency, and anything exotic still has "Open with" as a fallback.
 */
@Composable
private fun InlineMediaPlayer(
    att: Attachment,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit
) {
    val context = LocalContext.current
    var uri by remember(att.data) { mutableStateOf<Uri?>(null) }
    var failed by remember(att.data) { mutableStateOf(false) }
    // The prepared player, captured so the speed row can drive it after playback begins.
    var player by remember(att.data) { mutableStateOf<MediaPlayer?>(null) }
    // The View itself, so play/pause/seek can be issued without going through MediaPlayer directly
    // (VideoView owns its lifecycle and gets upset if you drive its player behind its back).
    var view by remember(att.data) { mutableStateOf<VideoView?>(null) }
    var speed by remember(att.data) { mutableStateOf(1f) }

    var durationMs by remember(att.data) { mutableIntStateOf(0) }
    var positionMs by remember(att.data) { mutableIntStateOf(0) }
    var playing by remember(att.data) { mutableStateOf(false) }
    var completed by remember(att.data) { mutableStateOf(false) }
    // While the user is dragging the scrub bar, this holds the finger's position and the poll below
    // stops writing to it — without this the two take turns setting the value and the thumb jumps
    // back and forth under the finger.
    var scrubbing by remember(att.data) { mutableStateOf(false) }
    var scrubMs by remember(att.data) { mutableStateOf(0f) }

    LaunchedEffect(att.data) {
        val u = withContext(Dispatchers.IO) { AttachmentAccess.contentUri(context, att) }
        if (u != null) uri = u else failed = true
    }

    // Poll the playhead. 200ms is imperceptible on a progress bar and costs nothing; a listener
    // would be nicer but MediaPlayer doesn't offer one for position.
    LaunchedEffect(view, playing, scrubbing) {
        while (playing && !scrubbing) {
            val vv = view
            if (vv != null) {
                positionMs = vv.currentPosition
                if (durationMs <= 0 && vv.duration > 0) durationMs = vv.duration
            }
            delay(200)
        }
    }

    // Auto-hide while playing, the way a video player should. Any tap resets the timer by flipping
    // chromeVisible, which re-runs this effect.
    LaunchedEffect(chromeVisible, playing) {
        if (chromeVisible && playing) {
            delay(3500)
            if (playing) onToggleChrome()
        }
    }

    // Leaving the viewer must stop playback; a dialog that closes while still playing audio is a
    // genuinely alarming thing to have happen.
    DisposableEffect(Unit) {
        onDispose {
            try { view?.stopPlayback() } catch (_: Throwable) {}
        }
    }

    fun applySpeed(newSpeed: Float) {
        speed = newSpeed
        val mp = player ?: return
        try {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(newSpeed)
            if (!wasPlaying) mp.pause()
        } catch (_: Throwable) {
            // Some codecs refuse a rate change; playback simply keeps its current speed.
        }
    }

    fun togglePlay() {
        val vv = view ?: return
        try {
            if (vv.isPlaying) {
                vv.pause()
                playing = false
            } else {
                // Restarting after the end: rewind first, otherwise start() resumes at the end and
                // instantly completes again.
                if (completed) {
                    vv.seekTo(0)
                    completed = false
                }
                vv.start()
                playing = true
            }
        } catch (_: Throwable) {}
    }

    when {
        uri != null -> {
            val mediaUri = uri!!
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            // No MediaController: the transport is Compose, below.
                            setVideoURI(mediaUri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = false
                                player = mp
                                durationMs = duration.coerceAtLeast(0)
                                try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Throwable) {}
                                start()
                                playing = true
                            }
                            setOnCompletionListener {
                                playing = false
                                completed = true
                                positionMs = durationMs
                            }
                            setOnErrorListener { _, _, _ ->
                                failed = true
                                true
                            }
                            view = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Audio has no picture, so it gets one: a glyph and the file name, which also keeps
                // the tap target meaningful instead of an invisible black rectangle.
                if (att.isAudio) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(att.name, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    }
                }

                // The whole surface is the show/hide tap target.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onToggleChrome() })
                        }
                )

                if (chromeVisible) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        val total = durationMs.coerceAtLeast(0)
                        val shown = if (scrubbing) scrubMs.toInt() else positionMs
                        Slider(
                            value = if (scrubbing) scrubMs else positionMs.toFloat().coerceIn(0f, total.toFloat()),
                            onValueChange = {
                                scrubbing = true
                                scrubMs = it
                            },
                            onValueChangeFinished = {
                                try { view?.seekTo(scrubMs.toInt()) } catch (_: Throwable) {}
                                positionMs = scrubMs.toInt()
                                // Seeking back from the end re-arms play.
                                if (scrubMs.toInt() < total) completed = false
                                scrubbing = false
                            },
                            valueRange = 0f..(if (total > 0) total.toFloat() else 1f),
                            enabled = total > 0,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { togglePlay() }) {
                                Icon(
                                    when {
                                        playing -> Icons.Default.Pause
                                        completed -> Icons.Default.Replay
                                        else -> Icons.Default.PlayArrow
                                    },
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color.White
                                )
                            }
                            Text(
                                "${formatClock(shown)} / ${formatClock(total)}",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.weight(1f))
                            listOf(0.5f, 1f, 1.5f, 2f).forEach { option ->
                                val selected = speed == option
                                val label = when (option) {
                                    0.5f -> "0.5×"
                                    1f -> "1×"
                                    1.5f -> "1.5×"
                                    else -> "2×"
                                }
                                Text(
                                    text = label,
                                    color = if (selected) Color.Black else Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.15f))
                                        .clickable { applySpeed(option) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        failed -> Text("Couldn't load this media", color = Color.White)
        else -> CircularProgressIndicator(color = Color.White)
    }
}

/** Milliseconds as m:ss (or h:mm:ss past an hour) for the transport's position/duration readout. */
private fun formatClock(millis: Int): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun NonPreviewableInfo(att: Attachment) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp)
    ) {
        Icon(
            Icons.Default.Launch,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(att.name, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "No in-app preview for this type. Save it, or open it in another app.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp
        )
    }
}
