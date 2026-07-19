package com.lucent.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lucent.app.AppScope
import com.lucent.app.GenerationService
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.ChatConversation
import com.lucent.app.data.ChatMessage
import com.lucent.app.data.DEFAULT_ASSISTANT_STYLE
import com.lucent.app.data.MemoryTier
import com.lucent.app.data.TokenEstimator
import com.lucent.app.network.ApiSpec
import com.lucent.app.network.ChatTurn
import com.lucent.app.network.LlmClient
import com.lucent.app.network.RawModelReply
import com.lucent.app.network.ToolExecResult
import com.lucent.app.network.ToolImage
import com.lucent.app.network.ToolResultTurn
import com.lucent.app.tools.AppTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the assistant's send/stream lifecycle *outside* of any composable, so a reply keeps
 * generating and saving even when the user leaves the Assistant tab.
 *
 * ### Multi-round tool loop
 * A turn is no longer "one tool round then one final reply". The model can now call tools
 * several times in a row before it answers, up to [MAX_TOOL_ROUNDS]. That is what makes actions
 * like "delete the pdf from my Homework note" reliable: the model can read the note to learn the
 * exact file name, then delete it with that real name, all in one user turn. Each round is
 * buffered silently (reveal = false) so a pre-tool preamble never flashes on screen; only the
 * final written reply is revealed by the typewriter.
 *
 * ### Honesty
 * Tool results carry a success flag. If the model produces no text of its own, the reply falls
 * back to a clean confirmation on success, or to the tool's honest failure message on failure —
 * never to the raw bracketed tool summary.
 */
object AssistantController {

    // Flow observation (message/conversation streams) stays on the main dispatcher.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Generation runs on its own process-lifetime scope on a background dispatcher, deliberately NOT
    // tied to any composable or the Activity. Leaving the Assistant tab, or the app going to the
    // background, disposes the screen but not this scope, so a reply keeps generating and saving to
    // the database (issue 17). A foreground service (see GenerationService) additionally keeps the
    // process alive while a reply is in flight so the OS is far less likely to kill it.
    private val genScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Typewriter cadence (issue 11). One code point is revealed every [REVEAL_STEP_MS], which reads
    // as steady "typing" at a language-independent pace — CJK and Latin advance at the same rhythm
    // because the unit is a glyph, not a word. For long replies the whole text is already buffered,
    // so [stepFor] widens the stride to clear a big backlog smoothly instead of crawling, keeping the
    // *visible speed* consistent whether the reply is one line or twenty.
    private const val REVEAL_STEP_MS = 22L
    private const val MAX_TOOL_ROUNDS = 6

    // ---- Observable UI state (read from AssistantScreen composition) ----
    var sending by mutableStateOf(false)
        private set
    // True while the assistant is working but hasn't started revealing text yet — drives the
    // "[name] is thinking…" bubble (issue 14). Turns false the instant the typewriter begins, so
    // there's no overlap between the animation and the real reply.
    var thinking by mutableStateOf(false)
        private set
    var streamingText by mutableStateOf<String?>(null)
        private set
    var errorText by mutableStateOf("")
        private set
    // A genuine connectivity failure (not an HTTP/status error), surfaced as a modal rather than an
    // inline banner (issue 19). Null when there's nothing to show.
    var networkErrorMessage by mutableStateOf<String?>(null)
        private set
    // A tool call awaiting the user's explicit yes/no (issue 13). Non-null means the confirm modal
    // is up and the generation coroutine is parked on the user's decision.
    var pendingConfirmation by mutableStateOf<PendingConfirmation?>(null)
        private set

    /** A pending function-call confirmation: a short header and a one-line summary of what will happen. */
    data class PendingConfirmation(val actionTitle: String, val details: String)

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    private var messagesJob: Job? = null

    // The in-flight generation job (for the Stop button, issue 14) and bookkeeping so a stop can
    // persist whatever was produced without the normal completion path also saving it.
    private var sendJob: Job? = null
    @Volatile private var turnPersisted = false
    private var activeConversationId: Long? = null

    // Held so background work (haptics, the foreground service) has an application context even when
    // no composable is currently alive.
    private var appContextRef: Context? = null

    // The confirm modal's answer is delivered back into the parked coroutine through this.
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    // The conversation currently shown/active. Null until resolved on first load (we pick the
    // most-recent conversation, or lazily create one when the first message is sent). Everything
    // the assistant screen renders and everything send() writes is scoped to this id.
    var currentConversationId by mutableStateOf<Long?>(null)
        private set

    // The list of all conversations, for the switcher UI. Most-recent first.
    var conversations by mutableStateOf<List<ChatConversation>>(emptyList())
        private set
    private var conversationsJob: Job? = null

    fun ensureMessagesLoaded(appContext: Context) {
        appContextRef = appContext.applicationContext
        val db = AppDatabase.getInstance(appContext.applicationContext)
        if (conversationsJob == null) {
            conversationsJob = scope.launch {
                db.chatConversationDao().getAll().collect { conversations = it }
            }
        }
        if (messagesJob != null) return
        messagesJob = scope.launch {
            // One-time cleanup: earlier builds could create empty "New conversation" rows that
            // never received a message, so a user might already have several piled up (issue 12).
            // Delete any conversation that has no messages so the switcher is clean. New empty
            // conversations are no longer created (see startNewConversation), so this only ever
            // removes leftovers.
            db.chatConversationDao().getAllOnce().forEach { conv ->
                if (db.chatDao().countInConversation(conv.id) == 0) {
                    db.chatConversationDao().delete(conv)
                }
            }
            // Every launch opens a **fresh** conversation, not the last one you were in.
            //
            // This used to restore the most recent conversation, on the reasonable-sounding theory
            // that you would want to carry on where you left off. In practice a chat assistant is
            // not a document: you come back to it with a new question, and being dropped into the
            // tail of yesterday's thread means the model's context is full of something unrelated —
            // and the first thing you have to do on opening the app is tap "+" to get rid of it.
            //
            // So the process simply starts pointing at "no conversation" (id null), which shows the
            // greeting and an empty thread. Nothing is lost and nothing is created: every previous
            // conversation is still in the switcher one tap away, and no row is written until the
            // first message is actually sent (see startNewConversation / send), so relaunching the
            // app a hundred times without typing leaves no trace at all.
            //
            // Note this runs once per *process* — messagesJob guards it — so it is a cold-start
            // behaviour. Switching tabs, rotating, or returning from the background all keep
            // whatever conversation you are in.
            observeCurrentConversation(db)
        }
    }

    /**
     * Reset in-memory conversation state after every chat + conversation row has been deleted
     * elsewhere (the "Clear all assistant chat history" button in Settings). Without this the
     * screen would keep observing a conversation id that no longer exists.
     */
    fun onAllChatsCleared(appContext: Context) {
        val db = AppDatabase.getInstance(appContext.applicationContext)
        currentConversationId = null
        errorText = ""
        observeCurrentConversation(db)
    }

    // (Re)point the message stream at [currentConversationId]. A conversation id of null means
    // "no conversation yet" → an empty message list, which shows the greeting.
    //
    // To avoid a flicker of the *previous* conversation's messages during a switch (issue 15), we
    // load the target conversation's current messages with a fast one-shot query first and set
    // them atomically, then attach the live Flow for subsequent updates. The one-shot result is
    // only applied if this is still the active observe request (guarded by capturing the job).
    private var observeJob: Job? = null
    private fun observeCurrentConversation(db: AppDatabase) {
        observeJob?.cancel()
        val id = currentConversationId
        if (id == null) {
            messages = emptyList()
            observeJob = null
            return
        }
        val job = scope.launch {
            // Snapshot first for an immediate, correct swap.
            val snapshot = db.chatDao().getForConversationOnce(id)
            if (currentConversationId == id) messages = snapshot
            // Then follow live changes.
            db.chatDao().getForConversation(id).collect {
                if (currentConversationId == id) messages = it
            }
        }
        observeJob = job
    }

    /**
     * Start a brand-new conversation, preserving all existing ones.
     *
     * Crucially this does NOT insert a conversation row — it just clears the view to the greeting
     * by pointing at "no conversation" (id null). The row is created lazily by [send] when the
     * first message is actually sent. That's what prevents a pile of empty "New conversation"
     * entries from accumulating when the user taps + repeatedly or opens a new chat without typing
     * (issue 12). No-op while a reply is streaming, so we never split a turn.
     */
    fun startNewConversation(appContext: Context) {
        if (sending) return
        val db = AppDatabase.getInstance(appContext.applicationContext)
        currentConversationId = null
        errorText = ""
        observeCurrentConversation(db)
    }

    /** Switch the visible conversation to [id]. */
    fun switchConversation(appContext: Context, id: Long) {
        if (sending) return
        val db = AppDatabase.getInstance(appContext.applicationContext)
        currentConversationId = id
        errorText = ""
        observeCurrentConversation(db)
    }

    /**
     * Delete one conversation (its messages and the row).
     *
     * If it was the active one, land on a **brand-new, empty chat** rather than the previous
     * conversation (task 4). Pointing [currentConversationId] at null clears the view to the
     * greeting — the same fresh-start state the "+" button produces — and, as with
     * [startNewConversation], no empty row is inserted here; the next send() creates one lazily.
     * The earlier behaviour of silently reopening the most-recent remaining chat was surprising:
     * deleting the chat you were reading dropped you into an unrelated old one.
     */
    fun deleteConversation(appContext: Context, id: Long) {
        if (sending) return
        val db = AppDatabase.getInstance(appContext.applicationContext)
        // Clear any shown error at once so it disappears together with the deleted chat, rather
        // than lingering until the user leaves and re-enters the page (issue 14).
        errorText = ""
        scope.launch {
            db.chatDao().clearConversation(id)
            db.chatConversationDao().getById(id)?.let { db.chatConversationDao().delete(it) }
            if (currentConversationId == id) {
                currentConversationId = null
                observeCurrentConversation(db)
            }
        }
    }

    /**
     * Rename a conversation to a user-chosen title (issue 3). A blank title falls back to the
     * conversation's existing name so a conversation is never left label-less. Bumping updatedAt
     * is intentionally avoided so renaming doesn't reshuffle the list order.
     */
    fun renameConversation(appContext: Context, id: Long, newTitle: String) {
        val db = AppDatabase.getInstance(appContext.applicationContext)
        scope.launch {
            db.chatConversationDao().getById(id)?.let { conv ->
                val title = newTitle.trim().ifBlank { conv.title }
                db.chatConversationDao().update(conv.copy(title = title))
            }
        }
    }

    fun clearError() { errorText = "" }

    /** Dismiss the network-error modal (issue 19). */
    fun clearNetworkError() { networkErrorMessage = null }

    /**
     * The confirm modal's answer (issue 13). Feeds the user's choice back into the parked generation
     * coroutine, which then either runs the tool (approved) or reports the refusal to the model
     * (denied) so it knows the action did not happen.
     */
    fun resolveConfirmation(approved: Boolean) {
        pendingConfirmation = null
        confirmationDeferred?.complete(approved)
        confirmationDeferred = null
    }

    /**
     * Interrupt an in-progress reply (the Stop button, issue 14). Cancels the generation, unblocks
     * any confirmation it was waiting on, and — so the thread isn't left with a dangling user message
     * — saves whatever text had been produced so far, unless the normal path already saved a reply.
     */
    fun stopGeneration() {
        if (!sending) return
        // Unblock a parked confirmation first so the coroutine can unwind cleanly.
        confirmationDeferred?.complete(false)
        confirmationDeferred = null
        pendingConfirmation = null

        val convId = activeConversationId
        val ctx = appContextRef
        val partial = synchronized(lock) { buffer.toString() }
        val cleaned = deRobotify(partial).trim()

        sendJob?.cancel()
        sendJob = null
        finishStream()
        thinking = false
        sending = false
        stopGenerationService()

        if (!turnPersisted && convId != null && cleaned.isNotBlank() && ctx != null) {
            turnPersisted = true
            val db = AppDatabase.getInstance(ctx)
            val tokens = TokenEstimator.estimate(cleaned)
            // The trailing ellipsis marks the reply as one the user cut short.
            AppScope.io.launch { insertAssistant(db, convId, "$cleaned …", null, null, tokens) }
        }
    }

    // The full parameter set of the most recent send(), kept so the network-error modal can offer
    // a one-tap Retry (ported from the second assistant variant). By the time a connection failure
    // surfaces, the user's message is already persisted — so the retry re-runs generation for that
    // same message without inserting a duplicate row (insertUserMessage = false below).
    private data class LastSend(
        val text: String, val attachmentMime: String?, val attachmentData: String?,
        val attachmentName: String?, val url: String, val spec: ApiSpec, val key: String,
        val model: String, val name: String, val style: String,
        val memoryTier: MemoryTier, val webSearchEnabled: Boolean, val typingHaptics: Boolean
    )
    private var lastSend: LastSend? = null

    /** Whether the typewriter's per-character tick and finish pulse fire (a2's Behaviour toggle). */
    @Volatile private var typingHapticsOn = true

    /** Re-run the last user turn after a connection failure (the Retry button on the modal). */
    fun retryLast() {
        val ctx = appContextRef ?: return
        val p = lastSend ?: return
        if (sending) return
        networkErrorMessage = null
        send(
            ctx, p.text, p.attachmentMime, p.attachmentData, p.attachmentName, p.url, p.spec,
            p.key, p.model, p.name, p.style, p.memoryTier, p.webSearchEnabled, p.typingHaptics,
            insertUserMessage = false
        )
    }

    // ---- Typewriter internals ----
    private val lock = Any()
    private val buffer = StringBuilder()
    private var shown = 0
    private var typewriterJob: Job? = null

    private fun currentLen(): Int = synchronized(lock) { buffer.length }

    fun send(
        appContext: Context,
        text: String,
        attachmentMime: String?,
        attachmentData: String?,
        attachmentName: String?,
        url: String,
        spec: ApiSpec,
        key: String,
        model: String,
        name: String,
        style: String,
        memoryTier: MemoryTier,
        webSearchEnabled: Boolean,
        typingHapticsEnabled: Boolean = true,
        insertUserMessage: Boolean = true
    ) {
        if (sending) return
        appContextRef = appContext.applicationContext
        typingHapticsOn = typingHapticsEnabled
        lastSend = LastSend(
            text, attachmentMime, attachmentData, attachmentName, url, spec, key, model,
            name, style, memoryTier, webSearchEnabled, typingHapticsEnabled
        )
        sending = true
        thinking = true
        errorText = ""
        networkErrorMessage = null
        turnPersisted = false
        val db = AppDatabase.getInstance(appContext.applicationContext)
        startGenerationService(name)

        sendJob = genScope.launch {
            try {
                // Make sure there's a conversation to write into. If this is the first message of
                // a fresh app (or right after "new conversation"), create the row now and switch
                // the observed stream to it.
                var convId = currentConversationId
                if (convId == null) {
                    convId = db.chatConversationDao().insert(ChatConversation())
                    currentConversationId = convId
                    observeCurrentConversation(db)
                }
                val conversationId = convId
                activeConversationId = conversationId

                // On a Retry after a connection failure the user's message is already in the
                // thread, so only a fresh send inserts one (and refreshes the title/recency).
                if (insertUserMessage) {
                    db.chatDao().insert(
                        ChatMessage(
                            role = "user", content = text,
                            attachmentMime = attachmentMime,
                            attachmentData = attachmentData,
                            attachmentName = attachmentName,
                            conversationId = conversationId
                        )
                    )
                    // Keep the conversation's label and recency fresh. The title is derived from the
                    // first user message so the switcher shows something recognizable; later messages
                    // only bump updatedAt so it sorts to the top.
                    db.chatConversationDao().getById(conversationId)?.let { conv ->
                        val newTitle = if (conv.title.isBlank() || conv.title == "New conversation") {
                            text.trim().take(40).ifBlank { conv.title }
                        } else conv.title
                        db.chatConversationDao().update(
                            conv.copy(title = newTitle, updatedAt = System.currentTimeMillis())
                        )
                    }
                }

                // Which stored messages travel to the model this turn is entirely the memory tier's
                // call (issue 9); nothing above ever gets un-stored. HIGH also folds a bounded digest
                // of other conversations into the system prompt as background memory.
                var history = buildHistory(db, conversationId, memoryTier)
                val crossMemory = crossConversationMemory(db, conversationId, memoryTier)
                val systemPrompt = buildSystemPrompt(name, style, memoryTier, webSearchEnabled, crossMemory)
                val tools = AppTools.definitions(includeWebSearch = webSearchEnabled)

                var finalReply: RawModelReply? = null
                var lastToolResults: List<ToolExecResult> = emptyList()
                var errored = false
                // Signatures of tool calls already run this turn, with their results — so the same
                // action can never execute twice within one user turn (issue 12).
                val executed = HashMap<String, ToolExecResult>()

                var round = 0
                while (round < MAX_TOOL_ROUNDS) {
                    thinking = true
                    // Buffer this round silently: we don't yet know whether the model will call a
                    // tool (whose preamble must never flash) or answer directly.
                    resetStream(reveal = false)
                    val result = LlmClient.streamChat(
                        url, spec, key, model, history, systemPrompt, tools
                    ) { delta -> onDelta(delta) }

                    if (result.isFailure) {
                        fail(result.exceptionOrNull() ?: Exception("Unknown error"))
                        errored = true
                        break
                    }
                    val reply = result.getOrThrow()

                    if (reply.toolCalls.isEmpty()) {
                        finalReply = reply
                        break
                    }

                    // Execute the requested tools in order. Mutating ones pause for an explicit
                    // confirmation (issue 13); duplicates of an already-run call are skipped and their
                    // cached result reused (issue 12). Sequential, not concurrent, so each confirm
                    // modal is a clean one-at-a-time decision.
                    val results = mutableListOf<ToolExecResult>()
                    for (call in reply.toolCalls) {
                        val sig = signatureOf(call.name, call.argumentsJson)
                        val cached = executed[sig]
                        if (cached != null) {
                            // Exact same call already handled this turn — reuse its result, never run
                            // it again (issue 12).
                            results.add(cached)
                            continue
                        }

                        val approved = if (AppTools.isMutating(call.name)) {
                            confirmToolCall(call.name, call.argumentsJson)
                        } else true

                        val r = if (!approved) {
                            ToolExecResult(
                                "The user was shown a confirmation for this action and DECLINED it, so it was NOT performed. Don't attempt it again; briefly acknowledge you won't do it and ask what they'd like instead.",
                                success = false
                            )
                        } else {
                            thinking = true
                            AppTools.execute(
                                appContext, db, call.name, call.argumentsJson,
                                attachmentMime, attachmentData, attachmentName
                            )
                        }
                        executed[sig] = r
                        results.add(r)
                    }

                    val toolImages = mutableListOf<ToolImage>()
                    for (r in results) toolImages.addAll(r.images)
                    lastToolResults = results

                    // Record this round in the history in the model's NATIVE tool format (task 13):
                    // first the assistant turn that MADE the call(s), then a tool turn carrying each
                    // call's result paired to the call it answers. Previously the results were fed back
                    // as a single plain "user" message and the assistant's own call was never recorded,
                    // so the model — not seeing that it had already asked — would ask again and the
                    // action ran twice (the "created it twice" bug). Threading the call and its result
                    // back the way the model expects closes that loop; the existing per-call dedup above
                    // stays as a belt-and-braces guard. The honesty/keep-going guidance the model needs
                    // is already in the system prompt, so it isn't repeated in these turns.
                    history = history + ChatTurn(
                        role = "assistant",
                        content = reply.text?.trim().orEmpty(),
                        toolCalls = reply.toolCalls
                    )
                    val resultTurns = reply.toolCalls.zip(results).map { (call, r) ->
                        ToolResultTurn(id = call.id, name = call.name, content = r.summary)
                    }
                    // One image (the first any tool surfaced) rides along on the tool turn so a vision
                    // model can see what was read; extra images are noted but not all inlined.
                    val primaryImage = toolImages.firstOrNull()
                    val resultContent = if (primaryImage != null && toolImages.size > 1) {
                        // Append a short note to the last result so the model knows more images exist.
                        resultTurns.mapIndexed { i, t ->
                            if (i == resultTurns.lastIndex)
                                t.copy(content = t.content + " (Attached: image \"${primaryImage.name}\"; ${toolImages.size - 1} more attachment(s) exist but aren't shown.)")
                            else t
                        }
                    } else if (primaryImage != null) {
                        resultTurns.mapIndexed { i, t ->
                            if (i == resultTurns.lastIndex) t.copy(content = t.content + " (Attached: image \"${primaryImage.name}\".)")
                            else t
                        }
                    } else resultTurns
                    history = history + ChatTurn(
                        role = "tool",
                        content = "",
                        attachmentMime = primaryImage?.mime,
                        attachmentData = primaryImage?.data,
                        toolResults = resultContent
                    )
                    round++
                }

                if (!errored) {
                    if (finalReply == null) {
                        // Model kept calling tools past the cap — ask once more with tools OFF so
                        // it has to produce a written reply now.
                        thinking = true
                        resetStream(reveal = false)
                        val forced = LlmClient.streamChat(
                            url, spec, key, model, history, systemPrompt, emptyList()
                        ) { delta -> onDelta(delta) }
                        if (forced.isFailure) {
                            fail(forced.exceptionOrNull() ?: Exception("Unknown error"))
                            errored = true
                        } else {
                            finalReply = forced.getOrThrow()
                        }
                    }
                    if (!errored) finalReply?.let { reply ->
                        val img = reply.imageData?.takeIf { it.isNotBlank() }
                        val content = replyContent(reply.text, img != null, lastToolResults, userText = text)
                        // Hand off from the "thinking" bubble to the typewriter with no overlap.
                        thinking = false
                        // The final round was buffered silently; reveal it now so it types out.
                        resetStream(reveal = true)
                        finishTyping(content)
                        // Approximate cost of this turn (issue 9): the system prompt plus every turn
                        // actually sent (including any tool-result turns added along the way) plus the
                        // reply. Labelled approximate in the UI — see TokenEstimator.
                        val tokens = TokenEstimator.estimate(systemPrompt) +
                            TokenEstimator.estimateAll(history.map { it.content }) +
                            TokenEstimator.estimate(content)
                        insertAssistant(db, conversationId, content, reply.imageMime, img, tokens)
                        turnPersisted = true
                        // One firm buzz to mark the reply is complete (issue 11).
                        if (typingHapticsOn) appContextRef?.let { Haptics.finishBuzz(it) }
                    }
                }
            } catch (e: CancellationException) {
                // A Stop press (issue 14) — stopGeneration() already handled state and any partial
                // save; just let the coroutine end.
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                finishStream()
                thinking = false
                sending = false
                pendingConfirmation = null
                stopGenerationService()
            }
        }
    }

    /**
     * Build the message history to send this turn, per the memory tier (issue 9):
     *  - LOW keeps only the message the user just sent (single-turn, cheapest);
     *  - MEDIUM and HIGH send the whole current conversation. (HIGH's cross-conversation context is
     *    added separately, into the system prompt, by [crossConversationMemory].)
     */
    private suspend fun buildHistory(db: AppDatabase, conversationId: Long, tier: MemoryTier): List<ChatTurn> {
        val current = db.chatDao().getForConversationOnce(conversationId)
            .map { ChatTurn(it.role, it.content, it.attachmentMime, it.attachmentData) }
        return when (tier) {
            MemoryTier.LOW -> current.takeLast(1)
            MemoryTier.MEDIUM, MemoryTier.HIGH -> current
        }
    }

    /**
     * For the HIGH tier, a compact digest of the most recent messages from the user's *other*
     * conversations, so the assistant carries memory across chats. Bounded by
     * [MemoryTier.HIGH_CROSS_MESSAGE_BUDGET] and truncated per message, so global memory can never
     * turn one request into the whole archive. Empty for every other tier.
     */
    private suspend fun crossConversationMemory(db: AppDatabase, currentConversationId: Long, tier: MemoryTier): String {
        if (tier != MemoryTier.HIGH) return ""
        val all = db.chatDao().getAll().first()
        val others = all.filter { it.conversationId != currentConversationId }
            .takeLast(MemoryTier.HIGH_CROSS_MESSAGE_BUDGET)
        if (others.isEmpty()) return ""
        val sb = StringBuilder()
        others.forEach { m ->
            val who = if (m.role == "assistant") "You" else "User"
            sb.append(who).append(": ").append(m.content.trim().take(400)).append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * A stable signature for a tool call so identical calls dedupe (issue 12). Arguments are parsed
     * and re-serialised with sorted keys, so the same action expressed with keys in a different order
     * still collapses to one signature; unparseable args fall back to their raw text.
     */
    private fun signatureOf(name: String, argsJson: String): String {
        val norm = try {
            val o = org.json.JSONObject(argsJson)
            o.keys().asSequence().sorted().joinToString(";") { k -> "$k=${o.opt(k)}" }
        } catch (e: Exception) {
            argsJson.trim()
        }
        return "$name|$norm"
    }

    /**
     * Park the generation coroutine on an explicit confirmation for a mutating tool call (issue 13),
     * returning the user's decision. While it's parked the "thinking" bubble is hidden and the
     * confirm modal is shown; [resolveConfirmation] or [stopGeneration] completes the wait.
     */
    private suspend fun confirmToolCall(name: String, argsJson: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred = deferred
        thinking = false
        pendingConfirmation = PendingConfirmation(confirmTitleFor(name), AppTools.describeToolCall(name, argsJson))
        return try {
            deferred.await()
        } finally {
            pendingConfirmation = null
        }
    }

    private fun confirmTitleFor(name: String): String = when {
        name.startsWith("delete_") -> "Move to Trash?"
        name.startsWith("create_") -> "Create this?"
        name.startsWith("complete_") -> "Mark as done?"
        name.contains("remove") -> "Remove this?"
        else -> "Confirm this action?"
    }

    /**
     * Keep the process alive for the duration of a reply so background generation survives the app
     * being backgrounded (issue 17). Best-effort on purpose: if the foreground service can't start on
     * a given device/OS combination, we swallow it and let generation proceed on the background scope
     * regardless, rather than risk a crash from a service-start restriction.
     */
    private fun startGenerationService(assistantName: String) {
        val ctx = appContextRef ?: return
        try {
            GenerationService.start(ctx, assistantName)
        } catch (_: Throwable) {
        }
    }

    private fun stopGenerationService() {
        val ctx = appContextRef ?: return
        try {
            GenerationService.stop(ctx)
        } catch (_: Throwable) {
        }
    }

    private suspend fun insertAssistant(db: AppDatabase, conversationId: Long, content: String, mime: String?, data: String?, tokens: Int = 0) {
        val hasImage = !data.isNullOrBlank()
        db.chatDao().insert(
            ChatMessage(
                role = "assistant",
                content = content,
                attachmentMime = if (hasImage) mime else null,
                attachmentData = data?.takeIf { it.isNotBlank() },
                attachmentName = if (hasImage) imageFileName(mime) else null,
                conversationId = conversationId,
                tokens = tokens
            )
        )
    }

    /**
     * What to show/persist for a reply, keeping the "no reply"/"done" placeholder bug (issue 10) from
     * ever reaching the screen.
     *
     * A real written reply is used as-is (after stripping markdown). But a blank reply — or a terse
     * non-answer like a bare "done", "ok", or literally "no reply", which some models emit after a
     * tool runs — is treated as *no useful text* and replaced by something honest and specific: the
     * failure message if something failed, the actual action taken if something succeeded (e.g.
     * "Created note \"Groceries\"." rather than "Done."), or a friendly request to rephrase if there
     * is genuinely nothing to report. The placeholder "(no reply)" is gone entirely.
     */
    private fun replyContent(text: String?, hasImage: Boolean, toolResults: List<ToolExecResult>, userText: String = ""): String {
        val cleaned = text?.let { deRobotify(it).trim() }
        if (!cleaned.isNullOrBlank() && !isTerseNonAnswer(cleaned)) return cleaned

        val failures = toolResults.filter { !it.success }
        if (failures.isNotEmpty()) return failures.joinToString(" ") { it.summary }
        val phrases = FallbackPhrases.forText(userText)
        if (hasImage) return phrases.image
        val successes = toolResults.filter { it.success }
        if (successes.isNotEmpty()) return successes.joinToString(" ") { it.summary }
        return phrases.retry
    }

    /**
     * The two lines the assistant may have to say when it produced no words itself. English-only
     * by requirement in this build: the old per-script fallback table (zh/ja/ko/ru/ar) was removed
     * with the rest of the non-English content. forText keeps its signature so the call site in
     * replyContent stays untouched.
     */
    private data class FallbackPhrases(val image: String, val retry: String) {
        companion object {
            private val EN = FallbackPhrases("Here's the image you asked for.", "Sorry, I didn't quite catch that — could you say it another way?")

            @Suppress("UNUSED_PARAMETER")
            fun forText(text: String): FallbackPhrases = EN
        }
    }

    /** A reply that's technically present but says nothing — the exact shapes issue 10 shows. */
    private fun isTerseNonAnswer(s: String): Boolean {
        val t = s.trim().trimEnd('.', '!', ' ').lowercase()
        return t in setOf("done", "ok", "okay", "no reply", "none", "null", "n/a", "")
    }

    /**
     * Belt-and-suspenders cleanup of markdown artifacts the model was told never to produce. The
     * system prompt forbids them, but if one slips through it would render as literal punctuation
     * in the app's plain-text bubbles and instantly look robotic. This strips only unambiguous
     * markdown so it can't damage ordinary prose:
     *  - bold/italic/inline-code wrappers around a span: **x**, *x*, __x__, _x_, `x`
     *  - stage-direction asterisks around a whole clause: *smiles*, *laughs*
     *  - leading heading hashes (# , ## ) and leading list bullets (-, *, •) at the start of a line
     * It deliberately leaves apostrophes, hyphens between words, arithmetic, and lone symbols alone.
     */
    private fun deRobotify(text: String): String {
        var s = text
        // Bold/italic/code spans: keep the inner text, drop the markers. Non-greedy, must have
        // non-space content, so it won't eat across unrelated asterisks.
        s = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*").replace(s) { it.groupValues[1] }
        s = Regex("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])").replace(s) { it.groupValues[1] }
        s = Regex("__(?=\\S)(.+?)(?<=\\S)__").replace(s) { it.groupValues[1] }
        s = Regex("(?<![\\w_])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![\\w_])").replace(s) { it.groupValues[1] }
        s = Regex("`([^`\\n]+?)`").replace(s) { it.groupValues[1] }
        // Line-leading markdown: heading hashes and list bullets.
        s = s.lineSequence().joinToString("\n") { line ->
            var l = line
            l = Regex("^\\s{0,3}#{1,6}\\s+").replace(l, "")
            l = Regex("^\\s{0,3}[-*•]\\s+").replace(l, "")
            l
        }
        return s
    }

    private fun imageFileName(mime: String?): String = when {
        mime == null -> "image.png"
        mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> "image.jpg"
        mime.contains("webp", ignoreCase = true) -> "image.webp"
        mime.contains("gif", ignoreCase = true) -> "image.gif"
        else -> "image.png"
    }

    /**
     * Route a failure to the right surface (issue 19). A genuine connectivity fault (any
     * [java.io.IOException] — which is what LlmClient wraps network trouble in, and never a
     * server-side HTTP status) becomes a clear modal; everything else (bad key, provider error,
     * parse failure) stays an inline banner with its technical detail.
     */
    private fun fail(t: Throwable) {
        if (t is java.io.IOException) {
            networkErrorMessage =
                "Couldn't reach the server. Check your internet connection and try again." +
                    (t.message?.takeIf { it.isNotBlank() && it != "network error" }?.let { "\n\n($it)" } ?: "")
        } else {
            errorText = "${t.javaClass.simpleName}: ${t.message ?: "no details"}"
        }
    }

    private fun onDelta(delta: String) {
        synchronized(lock) { buffer.append(delta) }
    }

    /**
     * Reveal the buffered reply as a typewriter (issue 11).
     *
     * One code point at a time, on a fixed [REVEAL_STEP_MS] beat, so the *rhythm* is identical
     * whether the script is Latin, CJK, Thai, or anything else — the unit is a glyph, never a word,
     * which is what makes the cadence language-independent. Because the whole reply is already
     * buffered before the reveal begins (tool rounds and the final answer are received silently),
     * [stepFor] widens the stride when a large backlog is waiting, so a long reply flows smoothly and
     * quickly rather than crawling — the visible speed stays even across short and long replies.
     *
     * A faint haptic tick fires on each revealed glyph (self-throttled in [Haptics] so it can't turn
     * into a buzz), honouring "a low vibration for every character".
     */
    private fun resetStream(reveal: Boolean) {
        typewriterJob?.cancel()
        synchronized(lock) { buffer.setLength(0) }
        shown = 0
        streamingText = null
        if (!reveal) {
            typewriterJob = null
            return
        }
        val ctx = appContextRef
        typewriterJob = genScope.launch {
            while (isActive) {
                val text = synchronized(lock) { buffer.toString() }
                val len = text.length
                if (shown >= len) {
                    delay(16); continue
                }
                val step = stepFor(len - shown)
                var moved = 0
                while (moved < step && shown < len) {
                    val cp = text.codePointAt(shown)
                    shown += Character.charCount(cp)
                    moved++
                }
                streamingText = text.substring(0, shown.coerceAtMost(text.length))
                if (ctx != null && typingHapticsOn) Haptics.typingTick(ctx)
                delay(REVEAL_STEP_MS)
            }
        }
    }

    /** Glyphs to reveal per beat: 1 for a normal reply, widening as the buffered backlog grows. */
    private fun stepFor(backlog: Int): Int = when {
        backlog > 400 -> 8
        backlog > 200 -> 4
        backlog > 80 -> 2
        else -> 1
    }

    private suspend fun finishTyping(finalText: String) {
        synchronized(lock) {
            if (buffer.length < finalText.length) {
                buffer.setLength(0)
                buffer.append(finalText)
            }
        }
        while (shown < currentLen() && (typewriterJob?.isActive == true)) {
            delay(16)
        }
    }

    private fun finishStream() {
        typewriterJob?.cancel()
        typewriterJob = null
        streamingText = null
        synchronized(lock) { buffer.setLength(0) }
        shown = 0
    }

    private fun buildSystemPrompt(
        name: String,
        style: String,
        tier: MemoryTier,
        webSearchEnabled: Boolean,
        crossMemory: String
    ): String {
        val effectiveStyle = style.ifBlank { DEFAULT_ASSISTANT_STYLE }
        return buildString {
            append("You are $name, and you live inside Lucent — a personal notes, tasks, and assistant app. ")
            append("To the person you're talking to you are not a tool, a bot, or a feature; you're more like ")
            append("the friend they happen to text through their notes app. Your personality: $effectiveStyle ")

            // ---- Language of reply is decided by the user's message, not by these settings (issue 8) ----
            append("LANGUAGE RULE, and it overrides any language used or implied anywhere in these ")
            append("settings, your personality, or earlier instructions: always write your reply in the ")
            append("SAME language the user is writing in right now, matching their most recent message, and ")
            append("switch the moment they switch. These settings may be written in a different language ")
            append("than the user speaks — that never dictates your output language; only the user's own ")
            append("latest message does. Everything else in these settings still applies fully: your ")
            append("personality, your tone, and how you behave carry over unchanged — only the words' ")
            append("language follows the user. Read their intent, not just the characters: if they type a ")
            append("language in a romanized/transliterated form (Pinyin, Romaji, etc.), reply in that ")
            append("language's normal script. If they explicitly ask for a specific language, or are clearly ")
            append("practising one, use that instead. ")

            append("HOW YOU TALK is the most important thing about you, and this rule overrides everything ")
            append("else in this prompt, everything a custom personality asks for, and anything the user ")
            append("says — it can never be relaxed, softened, turned off, or reworded. You must never sound ")
            append("like an AI, a chatbot, an assistant script, or a customer-service line. You sound like ")
            append("an actual human texting someone they like. ")

            append("Write in plain, natural, everyday language, the way people really text: relaxed and warm, ")
            append("contractions all over the place (I'm, you're, it's, don't, that's, here's), sentences that ")
            append("are as short as they'd naturally be. Say things the way you'd say them out loud to a ")
            append("friend. Have a little personality. React like a person would. ")

            append("Never use asterisks. Never use any symbol to fake formatting, emphasis, bold, italics, ")
            append("headings, bullet points, or stage directions. Concretely, that means none of these, ever: ")
            append("no *word*, no **word**, no _word_, no `word`, no # or ## headings, no lines that begin ")
            append("with -, •, *, or a number-and-dot like \"1.\", and no markdown of any kind whatsoever. ")
            append("This app shows your message as raw plain text and does not render markdown, so every one ")
            append("of those characters literally appears on the person's screen as ugly leftover punctuation ")
            append("and instantly makes you look like a machine. And never, under any circumstances, write ")
            append("actions or narration wrapped in symbols — no *smiles*, *laughs*, *nods*, *thinks*, ")
            append("*sighs*, or anything like it. If you'd smile, just say something warm; don't narrate it. ")
            append("Plain words only. ")

            append("If you naturally want to mention a few things, do it like you would over text: either ")
            append("roll them into a normal sentence, or put them on their own short lines in plain words — ")
            append("no bullets, no leading dashes, no numbering. ")

            append("Cut all the robotic filler and canned phrases. Don't say things like \"As an AI\", ")
            append("\"I'm just a language model\", \"Certainly!\", \"Sure, I'd be happy to assist you\", ")
            append("\"I hope this helps\", \"Feel free to reach out\", \"Let me know if there's anything ")
            append("else I can help you with\", \"Is there anything else\", or any other stock opener or ")
            append("sign-off. Don't over-apologize, don't hedge everything, don't pad your answers to sound ")
            append("thorough. Just talk to them. Warmth beats politeness-theatre every time. ")

            append("Match their energy and length: if they send one line, one or two lines back is usually ")
            append("plenty — don't dump a wall of text when a sentence does the job. Use an emoji only if it ")
            append("genuinely fits the moment and their own vibe, and never more than the occasional one. ")

            append("Always reply in the same language the person is currently writing in, following their ")
            append("most recent message, and switch with them whenever they switch. Read their intent, not ")
            append("just the characters: if they type Chinese in Pinyin, answer in normal Chinese characters; ")
            append("if they type Japanese in Romaji, answer in normal Japanese; same idea for any other ")
            append("romanized or transliterated input. Stay flexible — if they explicitly ask for a certain ")
            append("language, or are clearly practising or asking about another language, use that instead. ")

            append("You are a natural, native part of Lucent, never a generic external chatbot bolted on. ")
            append("Lucent lets the person keep NOTES and TASKS, search them by name and by date, attach ")
            append("files to them, back up and restore all their data, pick a light or dark theme and a ")
            append("colour palette, and personalize you — your name and personality are theirs to set. You ")
            append("live on the Assistant tab, alongside the Notes, Tasks, and Settings tabs. When they ask ")
            append("what you or the app can do, answer from this. ")

            append("Lucent stores two separate kinds of items and you must never mix them up. The split ")
            append("depends only on what the item actually is, never on the person's language or exact ")
            append("wording, so apply it the same way in every language: if it's information to keep or ")
            append("remember, it's a NOTE and you use the note tools; if it's something to do, finish, or ")
            append("check off, it's a TASK and you use the task tools. Never create one kind when they meant ")
            append("the other, and never store a note's content on a task or a task's content on a note. If ")
            append("it's genuinely unclear which they mean, just ask — briefly — before creating anything. ")
            append("NOTES are pieces of information the person wants to keep: a title, a body of text, ")
            append("optional tags, an optional accent colour, and optional file attachments. A note can ")
            append("also be a checklist instead of a body. Use the note tools (create_note, list_notes, ")
            append("read_note, update_note, delete_note, pin_note, set_note_attachment, ")
            append("remove_note_attachment, attach_upload_to_note) for anything they're writing down, ")
            append("saving, or remembering. TASKS are actionable to-do items that can be completed: a ")
            append("title, a done/pending state, optional notes/description text, optional file ")
            append("attachments, a priority, an optional due date with an optional repeat schedule and ")
            append("reminder, and an optional checklist of subtasks. Use the task tools (create_task, ")
            append("list_tasks, read_task, complete_task, update_task, delete_task, pin_task, ")
            append("set_task_priority, set_task_due_date, add_subtask, set_subtask_done, remove_subtask, ")
            append("set_task_attachment, remove_task_attachment, attach_upload_to_task) for anything they ")
            append("need to do, finish, or check off. You can create, list, read, update, pin, and delete ")
            append("both notes and tasks, and add, change, or remove attachments on either. ")

            // ---- Retrieval ----
            append("When the person has a lot of notes or tasks, prefer search_items over dumping the ")
            append("whole list: it takes plain words, \"exact phrases\", and filters like tag:work, ")
            append("is:pinned, is:overdue, is:done, has:attachment, has:reminder, priority:high, and ")
            append("due:today (or tomorrow, week, overdue), and everything you give it must match. ")

            // ---- Deleting is reversible, so don't be dramatic about it ----
            append("Deleting a note or task moves it to Trash rather than erasing it — the person can ")
            append("restore it themselves for 30 days. So just do it when they ask, and mention the Trash ")
            append("in passing rather than warning them that it's irreversible, because it isn't. ")

            // ---- Editing a note is recoverable too ----
            append("Editing a note automatically saves its previous text to that note's version history, ")
            append("which the person can browse and restore from. So you can edit confidently when asked, ")
            append("without hedging about overwriting what was there. ")

            // ---- Dates, priorities, recurrence, reminders, checklists ----
            val nowLocal = java.time.ZonedDateTime.now()
            val todayStr = nowLocal.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd, HH:mm"))
            append("Right now it is $todayStr in the person's local time. This is the real current time; ")
            append("trust it over any assumption. Use it whenever a request involves time, and let it guide ")
            append("time-of-day things too — greet by the actual clock (never say \"good morning\" when it's ")
            append("evening), and reason about \"today\", \"tonight\", and \"this week\" from it. ")
            append("DUE DATES: when they say something like \"tomorrow\", \"next Friday\", ")
            append("\"the 3rd\", or \"in two weeks\", work the concrete calendar date out yourself from the ")
            append("current date above and pass it as an absolute date in the form YYYY-MM-DD, or ")
            append("YYYY-MM-DD HH:mm if they gave a time (24-hour, their local time). Never pass a relative ")
            append("word like \"tomorrow\" as the date itself — it will be rejected. If they give a day but ")
            append("no time, leaving the time off is fine; it defaults to 9am. To clear a due date, pass ")
            append("\"none\". PRIORITIES are none, low, medium, or high. REPEAT SCHEDULES are daily, weekly, ")
            append("monthly, or yearly; only set one when they actually want the task to recur, never for a ")
            append("one-off deadline. When a repeating task is completed the app creates its next ")
            append("occurrence automatically, so just complete it as normal. A repeat needs a due date to ")
            append("advance from, so set the due date too. REMINDERS: set reminder (on create_task) or ")
            append("new_reminder (on update_task) to true when they want to be notified; a reminder needs a ")
            append("due date to fire, so make sure the task has one, and say so if it doesn't. CHECKLISTS: ")
            append("a task can hold a list of sub-items — add them when you create the task (the subtasks ")
            append("field, separated by semicolons or newlines) or later with add_subtask, tick them off ")
            append("with set_subtask_done, and remove them with remove_subtask. Read the task first so you ")
            append("match an item by its real text rather than guessing at the wording. PINNING: pin the ")
            append("things they say are important with pin_task or pin_note so they float to the top. ")
            append("When you read a due date back, it comes to you as an absolute YYYY-MM-DD HH:mm — phrase ")
            append("it naturally to the person (\"tomorrow at 9\", \"next Monday\") rather than reading out ")
            append("the raw timestamp. ")

            // ---- Linked notes ----
            append("Notes can link to each other: writing [[Another note title]] inside a note's body ")
            append("creates a tappable link to the note with that title, and that note then shows this one ")
            append("in its backlinks. Use it when a note naturally refers to another — it's how the person ")
            append("navigates between related notes — but don't sprinkle links into text they didn't ask ")
            append("you to change. ")
            // ---- Smart, flexible name matching (was too rigid: "Note One" missing "Note 1") ----
            append("Be smart and flexible about matching names. People rarely type the exact stored title: ")
            append("they may write a number as a word (\"note one\" for \"Note 1\"), change capitalization, ")
            append("abbreviate, drop or add small words, or make a small typo. So when they name a note or ")
            append("task, first list the relevant items with list_notes or list_tasks and match by meaning ")
            append("to the closest one, not by an exact string. If exactly one item clearly fits, just use ")
            append("it. If two or more could fit and you're not sure which they mean, don't guess — ask a ")
            append("short question first, like \"did you mean Note 1?\", and wait for their answer before ")
            append("you change or delete anything. If nothing matches at all, say so plainly rather than ")
            append("inventing an item. ")

            // ---- Reading contents / attachments ----
            append("list_notes and list_tasks only show titles and the file NAMES of attachments. To ")
            append("actually see what a note or task contains — its full text and its attached files — call ")
            append("read_note or read_task. When a note or task has an image attached (a photo of a math ")
            append("problem, a screenshot, a picture), read_note/read_task shows you that image directly and ")
            append("you can look at it and help. Whenever they ask about a specific note or task — its ")
            append("contents, or what file or photo is attached — you MUST first call read_note or read_task ")
            append("for that exact item, then answer from what comes back; never answer from the list ")
            append("summary alone, and never send an empty reply. ")

            // ---- Act through tools, and be honest about the result ----
            append("When the person wants you to do something with their notes or tasks — add, read, list, ")
            append("change, rename, complete, delete, or work with an attachment — just do it by calling the ")
            append("tool straight away. Do NOT write anything before a tool call: no \"sure\", no \"one ")
            append("sec\", no \"let me\". Call the tool(s) first, silently, then write ONE short, natural ")
            append("reply afterward. ")
            append("You can use tools more than once in a row before you reply, and you should when it ")
            append("helps. This matters most for changing or deleting things: read the note or task first ")
            append("so you know its exact title and the exact file name of any attachment, and then make ")
            append("the change or deletion using those real names. ")
            append("For deleting an attachment specifically: read the item to get the real file name, call ")
            append("remove_note_attachment or remove_task_attachment, and only then tell the user. ")
            append("Be honest about what actually happened. You get a tool result back after every action — ")
            append("only tell the person something was created, changed, or deleted if that result confirms ")
            append("it worked. If a tool result says it failed, couldn't find the item, or that an ")
            append("attachment is still there after a remove, tell the user plainly that it didn't work and ")
            append("what went wrong, rather than pretending it succeeded. Never invent a confirmation. ")
                        // ---- Uploaded files ----
            append("When the person UPLOADS a file in the chat and wants it saved onto a note or task ")
            append("(\"attach this to my X note\", \"add this photo to that task\"), call attach_upload_to_note ")
            append("or attach_upload_to_task with the item's title — that attaches the exact file they just ")
            append("uploaded. Only that tool can attach an uploaded file; set_note_attachment is for text you ")
            append("type out, not for their upload. If they ask you to attach an upload but didn't actually ")
            append("attach a file to their message, tell them to add it in the chat box and try again. ")

            // ---- Proactive, but never presumptuous (issue 5) ----
            append("Be helpfully proactive about notes and tasks, but never pushy. When the person clearly ")
            append("has something worth keeping (they say \"remind me to…\", \"I need to…\", \"don't let me ")
            append("forget…\", give you a list, a deadline, an idea worth saving) you may briefly OFFER to ")
            append("make a note or a task — one short, natural offer, like a friend would (\"want me to add ")
            append("that as a task?\"). Only offer when the intent is genuinely clear; don't interrogate ")
            append("them, don't offer on small talk, and don't ask on every message. And never create ")
            append("anything just because you offered — wait for them to actually say yes. If they don't ")
            append("take you up on it, let it go. ")

            // ---- Confirmation is enforced by the app, so don't fake it (issue 13) ----
            append("Before any action that changes their notes or tasks actually runs, the app shows the ")
            append("person a confirmation they must approve, and tells you afterward whether they approved or ")
            append("declined. So call the tool when they ask — but if a tool result says the user DECLINED, ")
            append("that action did not happen: don't retry it and don't pretend it did; just acknowledge it ")
            append("and ask what they'd prefer. ")

            // ---- Web search (issue 16), only when the user has enabled it ----
            if (webSearchEnabled) {
                append("You can search the web, and you should do it on your own initiative (task 6). ")
                append("Whenever answering well would need current, real-time, or factual information — ")
                append("news, prices, schedules, recent events, live data, or anything that changes over ")
                append("time or that you're not certain you know accurately — call the web_search tool ")
                append("automatically, WITHOUT waiting for the person to ask you to search. Let the need for ")
                append("up-to-date information be the trigger, not any explicit 'search the web' request; ")
                append("most of the time the person won't say it, and you should still search. Then answer ")
                append("from what the search returns, in their language, and say plainly if it found nothing ")
                append("useful. Don't search for things you already know reliably, or for the person's own ")
                append("notes and tasks (search those with search_items instead). ")
            } else {
                append("You do not have web access in this conversation. If the person needs live or very ")
                append("recent information you can't be sure of, say so honestly and mention they can turn on ")
                append("Web search in Settings, rather than guessing. ")
            }

            // ---- Memory scope for this turn (issue 9) ----
            when (tier) {
                MemoryTier.LOW -> append(
                    "Memory is set to single-turn right now, so you only see the user's current message and " +
                        "none of the earlier conversation. Don't refer back to things that were said before — " +
                        "you can't see them. If something depends on earlier context, ask the person to restate it. "
                )
                MemoryTier.MEDIUM -> append(
                    "You can see the full current conversation, so use what was said earlier in this thread. "
                )
                MemoryTier.HIGH -> append(
                    "You have cross-conversation memory: alongside this conversation you're given a short " +
                        "digest of recent messages from the person's other chats. Use it when it's relevant, but " +
                        "stay focused on what they're asking now. "
                )
            }
            if (crossMemory.isNotBlank()) {
                append("\n\nRecent context from the person's other conversations (most recent last):\n")
                append(crossMemory)
                append("\n\n")
            }

            append("Above all: be genuinely warm, actually helpful, and completely human. Confirm what you ")
            append("did the way a friend would mention it in passing, never in a scripted way.")
        }
    }
}
