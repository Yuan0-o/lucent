package com.lucent.app.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The on-device GGUF assistant engine (task: local large-model assistant).
 *
 * Everything llama.cpp-related funnels through here, and through **one dedicated background
 * thread** ([llmDispatcher]). That single decision is what delivers the task's hard requirements:
 *
 *  - **No lag, no freezes.** The UI thread never calls native code. Loading a multi-gigabyte model
 *    and decoding tokens both happen on the llm thread; the UI only ever observes state and
 *    receives streamed text through the existing typewriter, exactly as with a cloud model.
 *  - **No crashes.** A llama context is not thread-safe; a single serial executor makes concurrent
 *    access impossible by construction (the native layer holds a mutex too, as a second belt).
 *    Every native call is also wrapped so a failure becomes a return code, never an abort.
 *  - **Memory released on exit.** [shutdown] frees sampler, context, and model, and MainActivity
 *    calls it when the activity is finishing — so the moment the user actually leaves the app the
 *    gigabytes come back. (If the OS kills the process instead, the kernel reclaims them anyway.)
 *
 * Simplicity is deliberate: no tools, no cross-conversation memory, no KV-cache reuse between
 * turns. Each generation receives a self-contained prompt (recent turns of the current chat) and
 * starts from a clean context. Fluency was named the highest priority, and a stateless turn can
 * never be desynchronized, never leak context, and never hit the "cache poisoned, output garbage"
 * class of bug.
 */
object LocalLlm {

    /**
     * Context window. Kept modest — prompt-processing time is what old devices feel most — but large
     * enough to hold the tool-catalogue system prompt plus several turns of the current chat and a
     * couple of tool-result rounds. Anything longer is tail-truncated in the native layer, never a
     * crash. (Was 2048 before local tool-calling existed; the tool guide needs the extra room.)
     */
    const val N_CTX = 4096

    /** Cap on new tokens per reply — long enough for a real answer, bounded so a turn always ends. */
    const val MAX_NEW_TOKENS = 512

    /** Chat turns (user+assistant messages) of the current conversation sent with each prompt. */
    const val HISTORY_TURNS = 8

    // Desktop adaptation: the engine DLL is packaged as a resource and extracted on first use
    // (see nativebridge/NativeLoader). loadLlmEngine prefers the Vulkan-enabled DLL on machines
    // that can run it and falls back to the CPU-only DLL everywhere else (settings task A4);
    // everything below this line is the Android file verbatim.
    private val available: Boolean = run {
        val ok = com.lucent.app.nativebridge.NativeLoader.loadLlmEngine()
        if (!ok) Log.e("LocalLlm", "native engine library missing — local models unavailable")
        ok
    }

    /** Whether the native engine was packaged for this ABI at all. */
    fun isSupported(): Boolean = available

    // One thread for every native call, for the model's whole lifetime.
    private val llmDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "LucentLocalLlm") }.asCoroutineDispatcher()
    private val llmScope = CoroutineScope(SupervisorJob() + llmDispatcher)

    @Volatile private var handle: Long = 0L
    @Volatile private var loadedPath: String? = null
    // The slot id of the resident model, so a switch to a different slot forces a clean reload even
    // if two slots ever shared a path (they don't today, but tracking the id makes the intent exact).
    @Volatile private var loadedSlotId: String? = null
    private val generating = AtomicBoolean(false)
    // True only while a model is actually being loaded into memory (the slow, multi-second first
    // load). The assistant reads this to show a distinct "loading the model…" line, so the wait is
    // visible and never looks like a hang — loading a multi-gigabyte model is expected to take time.
    private val loading = AtomicBoolean(false)

    /** Offload-all sentinel for GPU mode; llama.cpp keeps on CPU any layer the device can't take. */
    private const val GPU_OFFLOAD_ALL = 999

    // The GPU choice (0 = CPU, the safe default; GPU_OFFLOAD_ALL = GPU). [desiredGpuLayers] is what
    // the user's setting asks for; [loadedGpuLayers] is what the resident model was actually loaded
    // with, so a change to the setting triggers a clean reload on the next send.
    @Volatile private var desiredGpuLayers: Int = 0
    @Volatile private var loadedGpuLayers: Int = 0

    /**
     * Apply the user's CPU/GPU choice. Called from the assistant before a local turn. Only records
     * the desired backend; the next [ensureLoaded] notices the mismatch and reloads the model cleanly
     * on the chosen backend (no separate async unload, so there is no race with a concurrent load).
     * Cheap and idempotent when nothing changes.
     *
     * DELIBERATE CONTRACT — a flip made WHILE a reply is generating is silent and deferred: the
     * in-flight reply keeps the backend it started on, and the change takes effect from the next
     * reply. Three things uphold this, and all three must survive future edits:
     *  1. This setter records a preference and nothing else — it must never stop, unload, or
     *     reload anything.
     *  2. A turn captures its choice once, at send: AssistantController receives useGpu as a
     *     parameter and calls this exactly once, before its single ensureLoaded.
     *  3. Every native call runs on [llmDispatcher]'s one thread, so even a stray ensureLoaded
     *     from some future call site queues behind a running decode instead of swapping the
     *     model out from under it.
     */
    fun setGpuEnabled(enabled: Boolean) {
        desiredGpuLayers = if (enabled) GPU_OFFLOAD_ALL else 0
    }

    /** True while a model is resident in memory. */
    fun isLoaded(): Boolean = handle != 0L

    /** True while a model is being loaded into memory (used to show a "loading…" state). */
    fun isLoading(): Boolean = loading.get()

    /** True while a local generation is in flight (used by Stop). */
    fun isGenerating(): Boolean = generating.get()

    /**
     * Threads for token generation: half the cores, clamped to 2..4. More threads than that hurts
     * on big.LITTLE phones (little cores drag the pace) and cooks the battery; fewer starves it.
     */
    private fun threadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /**
     * Make sure the imported model is loaded, loading it if needed. Safe to call every send:
     * a second call with the same file AND the same backend is a no-op, and a changed file or a
     * flipped CPU/GPU choice swaps cleanly. If a GPU load fails (e.g. a flaky Vulkan driver), it
     * transparently retries on CPU rather than failing — GPU is opt-in, so it must never be able to
     * take the whole feature down. Returns false when there is no model, the ABI is unsupported, or
     * even the CPU load failed.
     */
    suspend fun ensureLoaded(context: Context): Boolean = withContext(llmDispatcher) {
        if (!available) return@withContext false
        // Load whichever slot is ACTIVE. A different active slot than the resident one is what makes
        // "switch models" release the old and load the new: the id/path no longer match, so the block
        // below unloads first. Only one model is ever resident.
        val activeSlot = LocalModelStore.activeSlot(context) ?: return@withContext false
        val file = LocalModelStore.activeModelFile(context) ?: return@withContext false
        if (handle != 0L &&
            loadedSlotId == activeSlot.id &&
            loadedPath == file.absolutePath &&
            loadedGpuLayers == desiredGpuLayers
        ) return@withContext true
        if (handle != 0L) {
            // A previous model is resident (a different slot, or a changed backend). Free it before
            // the new one loads so the peak footprint is one model, not two.
            nativeUnload(handle)
            handle = 0L
            loadedPath = null
            loadedSlotId = null
        }
        val wantGpu = desiredGpuLayers
        fun attempt(gpuLayers: Int): Long = try {
            nativeLoad(file.absolutePath, N_CTX, threadCount(), gpuLayers)
        } catch (t: Throwable) {
            Log.e("LocalLlm", "load failed (gpuLayers=$gpuLayers)", t)
            0L
        }
        loading.set(true)
        try {
            var used = wantGpu
            var h = attempt(wantGpu)
            if (h == 0L && wantGpu > 0) {
                // GPU offload didn't take — fall back to CPU so the feature still works.
                Log.w("LocalLlm", "GPU load failed; falling back to CPU")
                used = 0
                h = attempt(0)
            }
            if (h != 0L) {
                handle = h
                loadedPath = file.absolutePath
                loadedSlotId = activeSlot.id
                loadedGpuLayers = used
            }
            h != 0L
        } finally {
            loading.set(false)
        }
    }

    /** JNI streaming callback — the native side looks this method up by name and signature. */
    interface PieceCallback {
        fun onPiece(piece: String)
    }

    /**
     * One full turn: template the [messages] (role → text, oldest first) with the model's own chat
     * template, then decode, streaming each UTF-8-complete piece to [onDelta] **on the llm thread**
     * (the caller's sink must be thread-safe; AssistantController's delta buffer is).
     *
     * Returns 0 on success, 1 if stopped by the user, negative on an engine error.
     */
    suspend fun generate(
        messages: List<Pair<String, String>>,
        onDelta: (String) -> Unit
    ): Int = withContext(llmDispatcher) {
        val h = handle
        if (h == 0L) return@withContext -1
        generating.set(true)
        try {
            val roles = Array(messages.size) { messages[it].first }
            val texts = Array(messages.size) { messages[it].second }
            val prompt = try {
                nativeChatPrompt(h, roles, texts, true)
            } catch (t: Throwable) {
                Log.e("LocalLlm", "template failed", t)
                ""
            }
            if (prompt.isBlank()) return@withContext -2
            val cb = object : PieceCallback {
                override fun onPiece(piece: String) {
                    onDelta(piece)
                }
            }
            try {
                nativeGenerate(h, prompt, MAX_NEW_TOKENS, cb)
            } catch (t: Throwable) {
                // Distinct from the native side's own -3 (empty tokens): -20 means the native call
                // itself threw into Java (e.g. a missing symbol), which is a different problem to chase.
                Log.e("LocalLlm", "nativeGenerate threw", t)
                -20
            }
        } finally {
            generating.set(false)
        }
    }

    /** Ask a running generation to stop after the current token. Callable from any thread. */
    fun stop() {
        val h = handle
        if (h != 0L) try {
            nativeStop(h)
        } catch (_: Throwable) {
        }
    }

    /**
     * Free the model and all native memory. Called when the user leaves the app (MainActivity
     * finishing) and when the model file is deleted/replaced in Settings. Runs on the llm thread;
     * an in-flight generation is stopped first, then freed once it has actually let go.
     */
    fun shutdown() {
        if (!available) return
        stop()
        llmScope.launch {
            val h = handle
            handle = 0L
            loadedPath = null
            loadedSlotId = null
            if (h != 0L) try {
                nativeUnload(h)
            } catch (t: Throwable) {
                Log.e("LocalLlm", "unload failed", t)
            }
        }
    }

    // ---- Native surface (lucent_llama.cpp) ----
    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    private external fun nativeChatPrompt(handle: Long, roles: Array<String>, texts: Array<String>, addAssistant: Boolean): String
    private external fun nativeGenerate(handle: Long, prompt: String, maxNew: Int, callback: PieceCallback): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeUnload(handle: Long)
}
