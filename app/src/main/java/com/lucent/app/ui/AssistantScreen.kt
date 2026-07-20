package com.lucent.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.ChatMessage
import com.lucent.app.data.ChatSearch
import com.lucent.app.data.MemoryTier
import com.lucent.app.data.SettingsRepository
import com.lucent.app.data.TokenEstimator
import com.lucent.app.network.ApiSpec
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingAttachment(val mime: String, val data: String, val name: String)

private const val MAX_CHAT_UPLOAD_BYTES = 1_000_000L

private fun queryFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = cursor.getString(idx)
        }
    }
    return name
}

/**
 * Scrolls to the **true** end of the chat: the last scrollable pixel, not merely the last message.
 *
 * ### Why the old version stopped short
 *
 * It tried to compute the landing position itself: take the last item, work out how much taller than
 * the viewport it is, and use that as a scroll offset so the item's bottom edge meets the viewport's
 * bottom edge. The arithmetic was right about the item and wrong about the list, because an item's
 * `size` is only the item. The chat's LazyColumn also has `contentPadding = PaddingValues(vertical =
 * 8.dp)` and `Arrangement.spacedBy(8.dp)`, and neither of those belongs to any item — so the scroll
 * consistently finished ~8dp above the real bottom. Close enough to look like it worked, close enough
 * to be irritating, and just far enough that the list could still scroll forward, which is why the
 * "jump to latest" button often stayed on screen after you had pressed it.
 *
 * There was a second, worse case hiding behind the same code: when the last item was *not currently
 * visible* — precisely the situation the jump button exists for — it fell back to `Int.MAX_VALUE` as
 * the scroll offset, i.e. it stopped computing and hoped the list would clamp a nonsensical number
 * into something sensible.
 *
 * ### What it does now
 *
 * It stops doing geometry and lets the list answer the question it alone can answer. `scrollToItem`
 * brings the final message into view, then a deliberately oversized `scrollBy` is clamped by the
 * LazyColumn at its own content bounds — which by definition include the bottom padding, the item
 * spacing, and anything else the layout added. Landing on the clamp *is* landing on the bottom.
 *
 * The loop is bounded because the content can still be growing while a reply streams in; it exists
 * to absorb a size change between the two calls, not to chase a moving target forever.
 */
private suspend fun LazyListState.scrollToLatest() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    // Get the last message on screen first. Cheap, and it makes the real measurements available.
    scrollToItem(lastIndex)
    // Then push past the end and let the list stop us exactly at it.
    var passes = 0
    while (canScrollForward && passes < 4) {
        if (scrollBy(100_000f) == 0f) break
        passes++
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AssistantScreen(active: Boolean = true) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    LaunchedEffect(Unit) { AssistantController.ensureMessagesLoaded(context) }
    val messages = AssistantController.messages
    val savedUrl by repo.baseUrl.collectAsState(initial = "")
    val savedSpecStr by repo.apiSpec.collectAsState(initial = "openai")
    val savedKey by repo.apiKey.collectAsState(initial = "")
    val savedModel by repo.model.collectAsState(initial = "")
    val assistantName by repo.assistantName.collectAsState(initial = "Lucent")
    val assistantStyle by repo.assistantStyle.collectAsState(initial = "")
    // Assistant memory & web settings, read live so a change in Settings takes effect on the next
    // send without reopening the screen (issues 9 and 16).
    val memoryTierKey by repo.memoryTier.collectAsState(initial = MemoryTier.DEFAULT.key)
    val webSearchEnabled by repo.webSearchEnabled.collectAsState(initial = false)
    val typingHapticsEnabled by repo.typingHapticsEnabled.collectAsState(initial = true)
    // Local-model routing (task: on-device GGUF assistant), read live like the rows above so
    // flipping the toggle in Settings changes where the very next send goes.
    val localModelEnabled by repo.localModelEnabled.collectAsState(initial = false)
    val localToolsEnabled by repo.localToolsEnabled.collectAsState(initial = false)
    val localGpuEnabled by repo.localGpuEnabled.collectAsState(initial = false)

    var input by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var conversationMenuOpen by remember { mutableStateOf(false) }
    // Search over chat *history*. The effect below finds every place the query occurs across all
    // conversations (task 9) so the results list can point at each individual hit.
    var conversationSearch by remember { mutableStateOf("") }
    // Rename dialog state: which conversation is being renamed and the working text.
    var renameTarget by remember { mutableStateOf<com.lucent.app.data.ChatConversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    // The conversation whose "⋮" was tapped (task 4), and the one awaiting a delete confirmation.
    var convoActions by remember { mutableStateOf<com.lucent.app.data.ChatConversation?>(null) }
    var convoToDelete by remember { mutableStateOf<com.lucent.app.data.ChatConversation?>(null) }
    var pendingSaveText by remember { mutableStateOf("") }
    var pendingSaveImage by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Per-reply "download files" modal (issue 6): the assistant message whose files are being
    // offered for download, or null when the modal is closed.
    var downloadDialogMsg by remember { mutableStateOf<ChatMessage?>(null) }
    // Payloads staged for a Storage-Access-Framework save. A single selected file saves directly; a
    // multi-file selection (or a whole-chat export, issue 7) is zipped.
    var pendingFileSave by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var pendingZipSave by remember { mutableStateOf<List<Pair<String, ByteArray>>?>(null) }

    // ---- History-search "jump to the exact hit" (task 9) ----
    // Every individual occurrence of the query across all chats, newest first. Drives the results
    // list while a search is active; each row jumps to one specific message.
    var deepMatches by remember { mutableStateOf<List<ChatSearch.MessageMatch>>(emptyList()) }
    // A tapped result waiting to be shown. Kept across the conversation switch it may trigger, until
    // that conversation's messages have loaded and we can scroll to the message.
    var pendingJump by remember { mutableStateOf<ChatSearch.MessageMatch?>(null) }
    // The message currently highlighted from a jump, and the exact character run within it to shade.
    var highlightMessageId by remember { mutableStateOf<Long?>(null) }
    var highlightStart by remember { mutableStateOf(0) }
    var highlightLen by remember { mutableStateOf(0) }
    // Bumped on every jump so the bounce animation re-fires even when re-selecting the same message.
    var highlightPulse by remember { mutableStateOf(0) }

    // History search (task 9). Debounced: each keystroke re-scans all messages for every place the
    // query occurs, so the results list can point at each individual hit.
    // Leaving the tab closes anything that was open over the chat (task 3): the conversation
    // switcher, its search, and any dialog. The chat itself *is* this tab's root, so it stays — you
    // should come back to your conversation, just not to a menu you left hanging over it.
    LaunchedEffect(active) {
        if (!active) {
            conversationMenuOpen = false
            conversationSearch = ""
            renameTarget = null
            convoActions = null
            convoToDelete = null
            showClearConfirm = false
        }
    }

    LaunchedEffect(conversationSearch, conversationMenuOpen) {
        val q = conversationSearch.trim()
        if (q.isEmpty()) {
            deepMatches = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(180) // debounce so we don't query on every keystroke
        val titles = AssistantController.conversations.associate { it.id to it.title }
        val matches = withContext(Dispatchers.IO) {
            val messageDocs = try {
                AppDatabase.getInstance(context.applicationContext).chatDao().getAllOnce().map { m ->
                    ChatSearch.MessageDoc(
                        conversationId = m.conversationId,
                        conversationTitle = titles[m.conversationId] ?: "",
                        messageId = m.id,
                        content = m.content,
                        timestamp = m.timestamp
                    )
                }
            } catch (t: Throwable) {
                emptyList()
            }
            ChatSearch.messageMatches(q, messageDocs)
        }
        deepMatches = matches
    }

    val streamingText = AssistantController.streamingText
    val sending = AssistantController.sending
    val thinking = AssistantController.thinking
    // While the on-device model is being loaded into memory, the thinking bubble says so explicitly,
    // so the (expected, multi-second) first-load wait reads as loading rather than a stall.
    val loadingModel = AssistantController.loadingModel
    val pendingConfirmation = AssistantController.pendingConfirmation
    val networkError = AssistantController.networkErrorMessage
    val shownError = if (AssistantController.errorText.isNotBlank()) AssistantController.errorText else localError

    DisposableEffect(Unit) {
        onDispose { AssistantController.clearError() }
    }

    // The chat list always shows the full current conversation; history search only affects the
    // conversation switcher, so the messages themselves are never filtered here.
    val filteredMessages = messages

    // Start the list already at the newest record. Messages are cached in the controller before
    // this screen composes, so reading messages.size here lets LazyColumn lay out with the last
    // item on-screen from the very first frame — no "show the oldest, then jump to the bottom"
    // step. Any value past the end is clamped by LazyColumn to the last valid index.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = AssistantController.messages.size.coerceAtLeast(0)
    )

    // ---- Follow mode ----
    //
    // While `autoScroll` is on, the list tracks the newest content: each streamed token and each new
    // message pulls the view to the bottom. Touching the list turns it off, and *only* the user can
    // turn it back on. That second half is the fix (task 1).
    //
    // ### Why it used to yank you back down
    //
    // Follow mode was re-armed by `LaunchedEffect(atBottom) { if (atBottom) autoScroll = true }` —
    // i.e. by the *observation* "the list currently can't scroll any further", regardless of how it
    // came to be in that state. That reads as reasonable and isn't, because `canScrollForward` is
    // derived from the current layout, and the layout is briefly unusual at exactly the wrong
    // moment: when a reply finishes, the streaming bubble is swapped for the stored message, and
    // during that swap there is a frame where the list reports nothing further to scroll to. One
    // such frame flipped `autoScroll` back on permanently — and the very next state change (the new
    // message landing) scrolled the user to the bottom, out of whatever they had scrolled up to read.
    // From the user's side this looks exactly like what was reported: it happens *when the reply
    // completes*, and scrolling up again doesn't help, because the same thing will happen next time.
    //
    // So the re-arm no longer trusts a snapshot of the layout. It waits for a **scroll that comes to
    // rest** at the bottom — `isScrollInProgress` falling back to false — which is something only a
    // real gesture (or a deliberate programmatic scroll, which can only run while already following)
    // produces. A transient layout state emits no such event and therefore cannot re-arm anything.
    //
    // The user keeps three explicit ways back into follow mode, all unchanged: scroll to the bottom,
    // tap the jump-to-latest button, or send a message.
    var autoScroll by remember { mutableStateOf(true) }

    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }

    // Any touch on the list hands the scroll position to the user.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) autoScroll = false
        }
    }
    // ...and only a scroll settling at the bottom hands it back.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !listState.canScrollForward) autoScroll = true
        }
    }
    LaunchedEffect(messages.size, streamingText, autoScroll) {
        // Don't yank the list to the bottom while a history-search jump is pending/landing (task 9).
        if (autoScroll && pendingJump == null) listState.scrollToLatest()
    }
    // Switching conversations should always land on the newest message, even when the two
    // conversations happen to have the same message count (so messages.size doesn't change).
    // Keying on the conversation id guarantees a jump-to-latest on every switch and re-enables
    // follow mode, which keeps the transition smooth and flicker-free (issue 15).
    LaunchedEffect(AssistantController.currentConversationId) {
        // A history-search jump may switch conversations on purpose to reach a hit; in that case we
        // want to scroll to the hit, not to the bottom, so we defer to the jump resolver (task 9).
        if (pendingJump == null) {
            autoScroll = true
            listState.scrollToLatest()
        }
    }
    // Resolves a tapped search result (task 9): once the target conversation's messages are loaded,
    // scroll that message into view, highlight the matched characters, and trigger the bounce. Keyed
    // on the message list too, so it re-runs after a conversation switch finishes loading.
    LaunchedEffect(pendingJump, AssistantController.currentConversationId, messages) {
        val jump = pendingJump ?: return@LaunchedEffect
        // Still on the old conversation — wait for the switch to bring in the right messages.
        if (AssistantController.currentConversationId != jump.conversationId) return@LaunchedEffect
        val idx = messages.indexOfFirst { it.id == jump.messageId }
        if (idx < 0) return@LaunchedEffect   // messages for the target chat haven't arrived yet
        // With messages present there are no leading items in the list, so the message's position in
        // `messages` is its item index. Bring it to the top of the viewport.
        listState.animateScrollToItem(idx.coerceAtLeast(0))
        highlightMessageId = jump.messageId
        highlightStart = jump.matchStart
        highlightLen = jump.matchLength
        highlightPulse++
        pendingJump = null
    }
    // When an error banner appears (e.g. an API failure), always bring it fully into view at the
    // very bottom, even if the user had scrolled up — otherwise a long chat hides the error code
    // off-screen and they have to hunt for it. (The existing "clear error on leaving" logic in
    // the DisposableEffect above is left untouched.)
    LaunchedEffect(shownError) {
        if (shownError.isNotBlank()) {
            autoScroll = true
            listState.scrollToLatest()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                if (mime.startsWith("image/")) {
                    val prepared = uriToChatImage(context, uri)
                    if (prepared != null) {
                        val (outMime, base64, name) = prepared
                        pendingAttachment = PendingAttachment(outMime, base64, name)
                    }
                } else {
                    val name = queryFileName(context, uri) ?: "file"
                    var overCap = false
                    val bytes: ByteArray? = try {
                        resolver.openInputStream(uri)?.use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            var tooBig = false
                            while (true) {
                                val r = stream.read(buf)
                                if (r == -1) break
                                total += r
                                if (total > MAX_CHAT_UPLOAD_BYTES) { tooBig = true; break }
                                out.write(buf, 0, r)
                            }
                            if (tooBig) { overCap = true; null } else out.toByteArray()
                        }
                    } catch (t: Throwable) { null }

                    if (bytes != null) {
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        pendingAttachment = PendingAttachment(mime, base64, name)
                    }
                    val asText: String? = if (bytes != null && bytes.none { it.toInt() == 0 }) {
                        try { String(bytes, Charsets.UTF_8) } catch (t: Throwable) { null }
                    } else null
                    if (asText != null) {
                        input = if (input.isBlank()) "${com.lucent.app.i18n.S.inputAttachedFile(name)}\n$asText" else "$input\n\n${com.lucent.app.i18n.S.inputAttachedFile(name)}\n$asText"
                    } else if (overCap) {
                        input = if (input.isBlank()) com.lucent.app.i18n.S.inputAttachedFileTooLarge(name) else "$input\n\n${com.lucent.app.i18n.S.inputAttachedFileTooLarge(name)}"
                    }
                }
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            scope.launch { context.contentResolver.openOutputStream(uri)?.use { it.write(pendingSaveText.toByteArray()) } }
        }
    }

    val imageSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri: Uri? ->
        val payload = pendingSaveImage
        if (uri != null && payload != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(payload.first, Base64.DEFAULT)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } catch (_: Throwable) {
                }
            }
        }
        pendingSaveImage = null
    }

    // Generic single-file save (issue 6): used when the user picks exactly one file to download from
    // a reply — e.g. just an attachment. The bytes are staged in [pendingFileSave].
    val fileSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val payload = pendingFileSave
        if (uri != null && payload != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(payload.second) }
                } catch (_: Throwable) {
                }
            }
        }
        pendingFileSave = null
    }

    // Zip save (issues 6 and 7): a multi-file reply download, or a whole-conversation export. The
    // entries (filename → bytes) are staged in [pendingZipSave] and written as a single .zip.
    val zipSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val entries = pendingZipSave
        if (uri != null && entries != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        java.util.zip.ZipOutputStream(out).use { zip ->
                            entries.forEach { (name, bytes) ->
                                zip.putNextEntry(java.util.zip.ZipEntry(name))
                                zip.write(bytes)
                                zip.closeEntry()
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
        pendingZipSave = null
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(com.lucent.app.i18n.S.deleteConversationTitle) },
            text = { Text(com.lucent.app.i18n.S.deleteConversationBodyAll) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    val id = AssistantController.currentConversationId
                    if (id != null) AssistantController.deleteConversation(context.applicationContext, id)
                }) { Text(com.lucent.app.i18n.S.actionDelete) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // The per-conversation actions sheet (task 4). Two full-width rows rather than dialog buttons,
    // because "Rename" and "Delete" are choices of what to do, not confirm/cancel of one thing —
    // and a destructive option deserves to be a deliberate, clearly-labelled target rather than a
    // word tucked into a button row.
    convoActions?.let { convo ->
        AlertDialog(
            onDismissRequest = { convoActions = null },
            title = { Text(convDisplayTitle(convo.title).ifBlank { com.lucent.app.i18n.S.conversationFallback }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                renameTarget = convo
                                renameText = convo.title
                                convoActions = null
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(com.lucent.app.i18n.S.actionRename)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                convoToDelete = convo
                                convoActions = null
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = OverdueColor)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(com.lucent.app.i18n.S.actionDelete, color = OverdueColor)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { convoActions = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Deleting from the list still confirms first — it destroys every message in that conversation,
    // and unlike the rest of the app there is no trash to fish it back out of.
    convoToDelete?.let { convo ->
        AlertDialog(
            onDismissRequest = { convoToDelete = null },
            title = { Text(com.lucent.app.i18n.S.deleteConversationTitle) },
            text = {
                Text(
                    com.lucent.app.i18n.S.deleteConversationBodyNamed(convDisplayTitle(convo.title).ifBlank { com.lucent.app.i18n.S.conversationFallback })
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = convo.id
                    convoToDelete = null
                    AssistantController.deleteConversation(context.applicationContext, id)
                }) { Text(com.lucent.app.i18n.S.actionDelete) }
            },
            dismissButton = { TextButton(onClick = { convoToDelete = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Rename dialog: opened from the actions sheet above.
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(com.lucent.app.i18n.S.renameConversationTitle) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(com.lucent.app.i18n.S.labelName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AssistantController.renameConversation(context.applicationContext, target.id, renameText)
                    renameTarget = null
                }) { Text(com.lucent.app.i18n.S.actionSave) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(com.lucent.app.i18n.S.actionCancel) } }
        )
    }

    // Per-reply download modal (issue 6): lists every file on the reply — the reply text and any
    // attachment — and lets the user choose which to download. One selection saves directly; several
    // are bundled into a zip.
    downloadDialogMsg?.let { msg ->
        DownloadFilesDialog(
            message = msg,
            assistantName = assistantName,
            onDismiss = { downloadDialogMsg = null },
            onSaveText = { fileName, text ->
                pendingSaveText = text
                saveLauncher.launch(fileName)
                downloadDialogMsg = null
            },
            onSaveFile = { fileName, bytes ->
                pendingFileSave = fileName to bytes
                fileSaveLauncher.launch(fileName)
                downloadDialogMsg = null
            },
            onSaveZip = { entries ->
                pendingZipSave = entries
                zipSaveLauncher.launch("lucent-reply.zip")
                downloadDialogMsg = null
            }
        )
    }

    // The function-call confirmation modal is NOT declared here any more (task 3). It is hosted by
    // LucentApp so it can appear on any tab, because generation outlives this screen — see the
    // documentation on [AssistantConfirmationDialog] for what went wrong when it lived here.
    // `pendingConfirmation` is still read above: it suppresses the thinking bubble while a decision
    // is outstanding, which is this screen's own business.

    // Network-error modal (issue 19): a genuine connectivity failure or timeout, surfaced clearly
    // rather than as a quiet inline line.
    networkError?.let { message ->
        AlertDialog(
            onDismissRequest = { AssistantController.clearNetworkError() },
            title = { Text(com.lucent.app.i18n.S.connectionProblem) },
            text = { Text(message) },
            confirmButton = {
                // One-tap recovery (ported from the second assistant variant): re-runs the same
                // turn without duplicating the already-saved user message.
                TextButton(onClick = { AssistantController.retryLast() }) { Text(com.lucent.app.i18n.S.actionRetry) }
            },
            dismissButton = {
                TextButton(onClick = { AssistantController.clearNetworkError() }) { Text(com.lucent.app.i18n.S.actionOk) }
            }
        )
    }

    // The whole column reserves the floating capsule's height at its bottom (LocalBottomBarInset),
    // which keeps the input row sitting just above the pill rather than trapped behind it — exactly
    // where it was before the capsule was made to float. The chat list fills the space above the
    // input row.
    Column(modifier = Modifier.fillMaxSize().padding(12.dp).padding(bottom = LocalBottomBarInset.current)) {
        // Conversation bar: start a new conversation (keeping old ones), switch between saved
        // conversations, or delete the current one. Kept to a single compact row so the chat
        // stays the focus.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: conversation switcher (label + count), tap to pick another conversation.
            //
            // The `weight` here is the fix for the squashed action icons (task 1). A Row measures
            // its *unweighted* children first, in order, each taking as much of the row as it asks
            // for — so the title chip, which could ask for up to ~244dp with a long name, was served
            // first and the three icon buttons on the right divided whatever scraps were left. On a
            // narrow screen that meant they were handed less than their 48dp and drew squashed,
            // which is exactly the deformed bin icon in the report.
            //
            // Weighting the chip inverts the order of service: the icons are measured first at their
            // natural size, and the chip is given what remains. It can no longer starve them, and the
            // title ellipsises instead — the correct thing to sacrifice, since a truncated name is
            // still readable and a truncated button is not.
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { conversationMenuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val current = AssistantController.conversations.firstOrNull { it.id == AssistantController.currentConversationId }
                    Icon(Icons.Default.Forum, contentDescription = null, tint = onGradient, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        current?.title?.let { convDisplayTitle(it) }?.ifBlank { com.lucent.app.i18n.S.conversationFallback } ?: com.lucent.app.i18n.S.newConversation,
                        color = onGradient,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // `fill = false` so a short title keeps the chip compact; a long one is
                        // capped by what the row can spare and ellipsised there.
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = com.lucent.app.i18n.S.a11ySwitchConversation,
                        tint = onGradient,
                        // Never let the caret be the thing that gets pushed out of the chip.
                        modifier = Modifier.size(24.dp)
                    )
                }
                DropdownMenu(expanded = conversationMenuOpen, onDismissRequest = { conversationMenuOpen = false }) {
                    val convos = AssistantController.conversations
                    // The whole menu is a fixed, compact width. The search field sits OUTSIDE the
                    // scrolling list below it, so it stays pinned while the conversations scroll
                    // (issue 1), and is trimmed to a smaller footprint (issue 2).
                    Column(modifier = Modifier.width(210.dp)) {
                        OutlinedTextField(
                            value = conversationSearch,
                            onValueChange = { conversationSearch = it },
                            // No placeholder here: the field is deliberately short (48.dp) to keep the
                            // dropdown compact, which clipped a placeholder line and left it looking
                            // like garbled text behind the caret (task 8). The leading magnifier icon
                            // already signals that this is the search field, so the label isn't needed.
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                        HorizontalDivider()

                        val searching = conversationSearch.isNotBlank()
                        if (searching) {
                            // ---- Search results: one row per individual hit (task 9) ----
                            // Each occurrence is listed separately, so the same message can appear
                            // more than once and several messages in one chat can each appear. Tapping
                            // a row lands on that exact message (switching chats if needed), where it's
                            // highlighted and bounced.
                            if (deepMatches.isEmpty()) {
                                DropdownMenuItem(text = { Text(com.lucent.app.i18n.S.noMatches) }, onClick = { }, enabled = false)
                            } else {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    deepMatches.forEachIndexed { i, match ->
                                        key(match.messageId, match.matchStart, i) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        conversationMenuOpen = false
                                                        conversationSearch = ""
                                                        // Don't let follow-mode fight the jump; the
                                                        // resolver scrolls to and highlights the hit.
                                                        autoScroll = false
                                                        pendingJump = match
                                                        if (AssistantController.currentConversationId != match.conversationId) {
                                                            AssistantController.switchConversation(
                                                                context.applicationContext,
                                                                match.conversationId
                                                            )
                                                        }
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        convDisplayTitle(match.conversationTitle).ifBlank { com.lucent.app.i18n.S.conversationFallback },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontSize = 11.sp,
                                                        color = onGradientMuted
                                                    )
                                                    // Preview with the matched run emphasised.
                                                    val previewed = remember(match.snippet, match.hitInSnippetStart, match.hitInSnippetLength) {
                                                        buildAnnotatedString {
                                                            val s = match.hitInSnippetStart.coerceIn(0, match.snippet.length)
                                                            val e = (match.hitInSnippetStart + match.hitInSnippetLength).coerceIn(s, match.snippet.length)
                                                            append(match.snippet.substring(0, s))
                                                            withStyle(SpanStyle(color = onGradient, fontWeight = FontWeight.Bold)) {
                                                                append(match.snippet.substring(s, e))
                                                            }
                                                            append(match.snippet.substring(e))
                                                        }
                                                    }
                                                    Text(
                                                        previewed,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontSize = 13.sp,
                                                        color = onGradientMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (convos.isEmpty()) {
                            DropdownMenuItem(text = { Text(com.lucent.app.i18n.S.noSavedConversations) }, onClick = { conversationMenuOpen = false }, enabled = false)
                        } else {
                            // ---- Not searching: the conversation switcher (tap switches, long-press renames) ----
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                convos.forEach { convo ->
                                    // Tap switches; the "⋮" opens this conversation's actions
                                    // (task 4). Rename used to be a long-press, which is the classic
                                    // invisible gesture: nothing on screen said it existed, so the
                                    // only people who ever renamed a conversation were the ones who
                                    // long-pressed it by accident. Delete wasn't reachable here at
                                    // all — you had to switch to a conversation first just to delete
                                    // it. A visible menu button fixes both.
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    conversationMenuOpen = false
                                                    AssistantController.switchConversation(context.applicationContext, convo.id)
                                                }
                                                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
                                        ) {
                                            Text(
                                                convDisplayTitle(convo.title).ifBlank { com.lucent.app.i18n.S.conversationFallback },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (convo.id == AssistantController.currentConversationId) onGradient else onGradientMuted
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                // Close the switcher first: the actions sheet is a
                                                // dialog, and leaving a dropdown open underneath it
                                                // stacks two popups that fight over dismissal.
                                                conversationMenuOpen = false
                                                convoActions = convo
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = com.lucent.app.i18n.S.a11yConversationOptions(convo.title.ifBlank { com.lucent.app.i18n.S.thisConversation }),
                                                tint = onGradientMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Right: export chat (issue 7), new conversation, delete current. Export sits to the
            // LEFT of the "+" as specified. Unweighted, so these are measured first and always get
            // their full touch target.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = {
                        pendingZipSave = buildChatExportEntries(messages, assistantName)
                        zipSaveLauncher.launch("lucent-chat.zip")
                    },
                    enabled = messages.isNotEmpty()
                ) {
                    Icon(Icons.Default.Archive, contentDescription = com.lucent.app.i18n.S.a11yExportChat, tint = onGradient)
                }
                IconButton(onClick = { AssistantController.startNewConversation(context.applicationContext) }) {
                    Icon(Icons.Default.Add, contentDescription = com.lucent.app.i18n.S.newConversation, tint = onGradient)
                }
                IconButton(
                    onClick = { showClearConfirm = true },
                    enabled = AssistantController.currentConversationId != null && messages.isNotEmpty()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = com.lucent.app.i18n.S.deleteConversationTitle.removeSuffix("?").removeSuffix("？"), tint = onGradient)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // The list plus an overlaid "jump to latest" button, so the button floats over the
        // bottom-right of the chat area only.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().hazeSource(state = LocalHazeState.current),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (messages.isEmpty()) {
                    item(key = "greeting") {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.align(Alignment.CenterStart).frostedGlass().padding(12.dp)) {
                                Text(
                                    com.lucent.app.i18n.S.assistantGreeting(assistantName),
                                    color = onGradient
                                )
                            }
                        }
                    }
                }
                items(filteredMessages, key = { it.id }) { msg ->
                    val isUser = msg.role == "user"
                    // When this message is the target of a history-search jump, shade the matched run
                    // and bounce the bubble twice (task 9). The Animatable is keyed to the message id
                    // so it belongs to this bubble; the effect re-fires whenever highlightPulse ticks.
                    val isHighlighted = msg.id == highlightMessageId
                    val bounce = remember(msg.id) { Animatable(1f) }
                    LaunchedEffect(msg.id, isHighlighted, highlightPulse) {
                        if (isHighlighted) {
                            repeat(2) {
                                bounce.animateTo(1.06f, tween(110))
                                bounce.animateTo(1f, tween(140))
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                                .graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
                                .frostedGlass()
                                .padding(12.dp)
                        ) {
                            // Assistant name tag at the top-left of the bubble (issue 15).
                            if (!isUser) {
                                Text(
                                    assistantName,
                                    color = onGradientMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                            msg.attachmentData?.let { base64 ->
                                if (isUser) {
                                    val bitmap = remember(base64) {
                                        val bytes = try {
                                            Base64.decode(base64, Base64.DEFAULT)
                                        } catch (t: Throwable) {
                                            null
                                        }
                                        if (bytes != null) decodeSampledBitmap(bytes) else null
                                    }
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = com.lucent.app.i18n.S.a11yAttachment,
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                        )
                                    } else {
                                        Text(com.lucent.app.i18n.S.imageUnreadable, color = Color.Red, modifier = Modifier.padding(bottom = 8.dp))
                                    }
                                } else {
                                    val fileName = msg.attachmentName ?: "image.png"
                                    Row(
                                        modifier = Modifier
                                            .padding(bottom = 8.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.White.copy(alpha = 0.10f))
                                            .clickable {
                                                pendingSaveImage = base64 to fileName
                                                imageSaveLauncher.launch(fileName)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = com.lucent.app.i18n.S.a11yDownloadFile(fileName), tint = onGradient)
                                        Text(fileName, color = onGradient, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }

                            // Long-press the message text to copy it to the clipboard. When this is
                            // the message a search jumped to, the matched run is shaded amber (task 9).
                            val contentText = if (isHighlighted && highlightLen > 0) {
                                buildAnnotatedString {
                                    val s = highlightStart.coerceIn(0, msg.content.length)
                                    val e = (highlightStart + highlightLen).coerceIn(s, msg.content.length)
                                    append(msg.content.substring(0, s))
                                    withStyle(SpanStyle(background = Color(0x66FFC107), fontWeight = FontWeight.Bold)) {
                                        append(msg.content.substring(s, e))
                                    }
                                    append(msg.content.substring(e))
                                }
                            } else {
                                buildAnnotatedString { append(msg.content) }
                            }
                            Text(
                                contentText,
                                color = onGradient,
                                modifier = Modifier.longPressCopy(context, msg.content)
                            )
                            if (!isUser) {
                                // Download opens a modal listing every file on this reply so the user
                                // picks what to save (issue 6).
                                IconButton(
                                    onClick = { downloadDialogMsg = msg },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = com.lucent.app.i18n.S.a11yDownloadReplyFiles, tint = onGradientMuted)
                                }
                                // Approximate token cost of this reply, bottom-right (issue 9). Hidden
                                // for older replies saved before token tracking (tokens == 0).
                                if (msg.tokens > 0) {
                                    Text(
                                        TokenEstimator.label(msg.tokens),
                                        color = onGradientMuted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.align(Alignment.End)
                                    )
                                }
                            }
                        }
                    }
                }
                if (streamingText != null) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.align(Alignment.CenterStart).frostedGlass().padding(12.dp)) {
                                Text(
                                    assistantName,
                                    color = onGradientMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                                Text(streamingText, color = onGradient)
                            }
                        }
                    }
                }
                // "[name] is thinking…" bubble (issue 14): shown while the assistant is working but
                // hasn't begun revealing text, and not while a confirmation modal is up.
                if (thinking && streamingText == null && pendingConfirmation == null) {
                    item(key = "thinking") {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.align(Alignment.CenterStart).frostedGlass().padding(12.dp)) {
                                ThinkingBubble(
                                    name = assistantName,
                                    tint = onGradient,
                                    mutedTint = onGradientMuted,
                                    loadingModel = loadingModel
                                )
                            }
                        }
                    }
                }
                if (shownError.isNotBlank()) {
                    item(key = "error") {
                        Text(
                            shownError,
                            color = Color(0xFFFFC1C1),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).longPressCopy(context, shownError)
                        )
                    }
                }
            }

            // One-tap return to the newest record, shown only while the user has scrolled up.
            // Extracted into its own composable so AnimatedVisibility resolves to the top-level
            // overload: called directly inside this Box (itself nested in a Column), the
            // ColumnScope/RowScope AnimatedVisibility extension overloads become ambiguous
            // candidates and the compiler rejects the call.
            JumpToLatestButton(
                visible = !atBottom,
                tint = onGradient,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                onClick = {
                    autoScroll = true
                    scope.launch { listState.scrollToLatest() }
                }
            )
        }

        if (pendingAttachment != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = onGradientMuted)
                Text(pendingAttachment?.name ?: "", color = onGradientMuted, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { pendingAttachment = null }) {
                    Icon(Icons.Default.Close, contentDescription = com.lucent.app.i18n.S.a11yRemoveAttachment, tint = onGradientMuted)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { filePickerLauncher.launch("*/*") }, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Default.AttachFile, contentDescription = com.lucent.app.i18n.S.a11yAttachFile, tint = onGradient)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(com.lucent.app.i18n.S.messagePlaceholder, fontSize = 13.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                modifier = Modifier.weight(1f).height(56.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (sending) {
                // While generating, the send button becomes a Stop button that interrupts the reply
                // (issue 14) — replacing the old non-interactive spinner.
                IconButton(
                    modifier = Modifier.height(56.dp),
                    onClick = { AssistantController.stopGeneration() }
                ) {
                    Icon(Icons.Default.Stop, contentDescription = com.lucent.app.i18n.S.a11yStopGenerating, tint = onGradient)
                }
            } else {
                IconButton(
                    modifier = Modifier.height(56.dp),
                    onClick = {
                        val text = input.trim()
                        val attachment = pendingAttachment
                        if (text.isBlank() && attachment == null) return@IconButton
                        // Local mode is AUTHORITATIVE. Once "use local model" is on, the assistant
                        // replies on-device and the cloud API is frozen — the send path must never
                        // silently fall back to it (that was the "it still calls the API" bug). If
                        // local is on but no model is loaded, the controller surfaces a clear
                        // "no model" error rather than reaching for the API. The cloud URL/model
                        // guard below therefore only applies when local mode is OFF.
                        val useLocal = localModelEnabled
                        if (!useLocal && (savedUrl.isBlank() || savedModel.isBlank())) {
                            localError = com.lucent.app.i18n.S.setupApiFirst
                            return@IconButton
                        }
                        input = ""
                        pendingAttachment = null
                        localError = ""
                        autoScroll = true
                        val spec = when (savedSpecStr) {
                            "anthropic" -> ApiSpec.ANTHROPIC
                            "google" -> ApiSpec.GOOGLE
                            else -> ApiSpec.OPENAI
                        }
                        AssistantController.send(
                            appContext = context.applicationContext,
                            text = text,
                            attachmentMime = attachment?.mime,
                            attachmentData = attachment?.data,
                            attachmentName = attachment?.name,
                            url = savedUrl, spec = spec, key = savedKey, model = savedModel,
                            name = assistantName, style = assistantStyle,
                            memoryTier = MemoryTier.fromKey(memoryTierKey),
                            webSearchEnabled = webSearchEnabled,
                            typingHapticsEnabled = typingHapticsEnabled,
                            useLocalModel = useLocal,
                            useLocalTools = localToolsEnabled,
                            useLocalGpu = localGpuEnabled
                        )
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = com.lucent.app.i18n.S.a11ySend, tint = onGradient)
                }
            }
        }
    }
}

/**
 * The floating "jump to latest" button that fades in while the user has scrolled up and taps back
 * to the newest message.
 *
 * Kept as a standalone composable on purpose. At its original call site it sat directly inside a
 * Box that is itself nested in the screen's root Column, which put a ColumnScope in the implicit
 * receiver chain. AnimatedVisibility has ColumnScope and RowScope extension overloads in addition
 * to the top-level one, so the compiler tried to bind the ColumnScope overload to a receiver that
 * wasn't the innermost one and failed ("cannot be called in this context with an implicit
 * receiver"). Inside this function no such scope is present, so AnimatedVisibility resolves
 * unambiguously to the top-level overload. The caller supplies the alignment via [modifier].
 */
@Composable
private fun JumpToLatestButton(
    visible: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = com.lucent.app.i18n.S.a11yJumpToLatest, tint = tint)
        }
    }
}

/**
 * The "[name] is thinking…" indicator (issue 14): a label followed by three dots that fade in and
 * out in a staggered wave, echoing the familiar chat "typing" animation. Purely decorative and
 * self-contained; it animates only while it's on screen.
 */
@Composable
private fun ThinkingBubble(name: String, tint: Color, mutedTint: Color, loadingModel: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (loadingModel) com.lucent.app.i18n.S.lmLoadingIndicator
            else com.lucent.app.i18n.S.thinkingIndicator(name),
            color = mutedTint,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        for (i in 0 until 3) {
            val dotAlpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Text(
                "•",
                color = tint,
                fontSize = 18.sp,
                modifier = Modifier.alpha(dotAlpha).padding(horizontal = 1.dp)
            )
        }
    }
}

/**
 * The per-reply "download files" chooser (issue 6). Lists every downloadable file on a reply — the
 * reply text and any attachment — with a checkbox each. Downloading one selected file saves it
 * directly; downloading several bundles them into a single zip. Checkbox state is keyed to the
 * message id so reopening on a different reply starts fresh.
 */
@Composable
private fun DownloadFilesDialog(
    message: ChatMessage,
    assistantName: String,
    onDismiss: () -> Unit,
    onSaveText: (fileName: String, text: String) -> Unit,
    onSaveFile: (fileName: String, bytes: ByteArray) -> Unit,
    onSaveZip: (entries: List<Pair<String, ByteArray>>) -> Unit
) {
    val hasText = message.content.isNotBlank()
    val attName = message.attachmentName
    val attData = message.attachmentData
    val hasAttachment = !attData.isNullOrBlank() && attName != null
    val textFileName = "lucent-reply.txt"

    var selText by remember(message.id) { mutableStateOf(hasText) }
    var selAtt by remember(message.id) { mutableStateOf(hasAttachment) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(com.lucent.app.i18n.S.downloadFilesTitle) },
        text = {
            Column {
                Text(com.lucent.app.i18n.S.downloadChoose, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                if (hasText) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selText = !selText }
                    ) {
                        Checkbox(checked = selText, onCheckedChange = { selText = it })
                        Text(com.lucent.app.i18n.S.downloadReplyTxt)
                    }
                }
                if (hasAttachment) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selAtt = !selAtt }
                    ) {
                        Checkbox(checked = selAtt, onCheckedChange = { selAtt = it })
                        Text(attName ?: com.lucent.app.i18n.S.a11yAttachment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!hasText && !hasAttachment) {
                    Text(com.lucent.app.i18n.S.downloadNone, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selText || selAtt,
                onClick = {
                    val entries = mutableListOf<Pair<String, ByteArray>>()
                    if (selText && hasText) entries.add(textFileName to message.content.toByteArray())
                    if (selAtt && hasAttachment) {
                        val bytes = try { Base64.decode(attData, Base64.DEFAULT) } catch (t: Throwable) { ByteArray(0) }
                        entries.add((attName ?: "attachment") to bytes)
                    }
                    when {
                        entries.isEmpty() -> onDismiss()
                        entries.size == 1 -> {
                            val (n, b) = entries.first()
                            if (n == textFileName) onSaveText(n, message.content) else onSaveFile(n, b)
                        }
                        else -> onSaveZip(entries)
                    }
                }
            ) { Text(com.lucent.app.i18n.S.actionDownload) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(com.lucent.app.i18n.S.actionCancel) } }
    )
}

/**
 * Build the entries for a whole-conversation export zip (issue 7): a single `chat.txt` transcript in
 * chronological order, followed by each attachment decoded back to its original bytes under a
 * numbered, sanitised filename. The transcript references each attachment by its in-zip name so the
 * two line up.
 */
private fun buildChatExportEntries(messages: List<ChatMessage>, assistantName: String): List<Pair<String, ByteArray>> {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    val sb = StringBuilder()
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    var attIndex = 1
    messages.forEach { m ->
        val who = if (m.role == "assistant") assistantName else com.lucent.app.i18n.S.exportYou
        val time = fmt.format(java.util.Date(m.timestamp))
        sb.append("[").append(time).append("] ").append(who).append(":\n")
        sb.append(m.content).append("\n")
        val data = m.attachmentData
        if (!data.isNullOrBlank()) {
            val entryName = "%02d_%s".format(attIndex, sanitizeExportName(m.attachmentName ?: "attachment"))
            val bytes = try { Base64.decode(data, Base64.DEFAULT) } catch (t: Throwable) { ByteArray(0) }
            entries.add(entryName to bytes)
            sb.append("[attachment: ").append(entryName).append("]\n")
            attIndex++
        }
        sb.append("\n")
    }
    entries.add(0, "chat.txt" to sb.toString().toByteArray())
    return entries
}

private fun sanitizeExportName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }

/**
 * Display mapping for a stored conversation title (localization task).
 *
 * A conversation is PERSISTED with the English sentinel "New conversation" until its first reply
 * earns it a real name (AssistantController auto-titles on that exact string — see its comparison
 * at newTitle). The sentinel must therefore stay English in the database; this maps it to the
 * catalog only at the moment it is shown, so a fresh conversation reads as the translated
 * phrase in Japanese while the auto-title logic keeps matching what is actually stored.
 */
private fun convDisplayTitle(title: String): String =
    if (title == "New conversation") com.lucent.app.i18n.S.newConversation else title
