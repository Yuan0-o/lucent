package com.lucent.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ToolAcc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var thoughtSignature: String? = null)

object LlmClient {

    // retryOnConnectionFailure lets OkHttp transparently re-establish a dropped socket (common on
    // mobile as the radio flips between wifi and cellular), and the call-level retry below adds a
    // couple of backed-off attempts on top for timeouts and resets. Together they are the "fix the
    // underlying connection stability" half of issue 19; the modal is the other half.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Generation parameters (issue 18). A single, provider-neutral place for the sampling knobs that
    // shape answer quality. A moderate temperature keeps replies natural without wandering into
    // incoherence, and a generous token ceiling stops longer answers being truncated mid-sentence —
    // a frequent cause of replies that read as illogical because they simply stopped.
    private const val TEMPERATURE = 0.6
    private const val TOP_P = 0.9
    private const val MAX_TOKENS = 2048

    // Call-level retry for transient network faults. HTTP errors are never retried (a 401 won't fix
    // itself); only connectivity exceptions are.
    private const val MAX_ATTEMPTS = 3
    private val RETRY_BACKOFF_MS = longArrayOf(400L, 1200L)

    private fun isTransientNetwork(t: Throwable): Boolean {
        // The controller-facing error is wrapped in ApiNetworkException; the retry decision cares
        // about the original cause.
        val e = if (t is ApiNetworkException) (t.cause ?: t) else t
        return when (e) {
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException,
            is java.net.ConnectException,
            is java.net.SocketException -> true
            else -> false
        }
    }

    suspend fun fetchModels(baseUrl: String, spec: ApiSpec, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.trimEnd('/') + "/models"
            val requestBuilder = Request.Builder().url(url).get()
            addAuthHeaders(requestBuilder, spec, apiKey)
            val response = client.newCall(requestBuilder.build()).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}: $bodyStr"))
            val json = JSONObject(bodyStr)
            val dataArray = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
            val ids = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                var id = item.optString("id", item.optString("name", ""))
                if (spec == ApiSpec.GOOGLE) id = id.removePrefix("models/")
                if (id.isNotBlank()) ids.add(id)
            }
            Result.success(ids)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendChat(
        baseUrl: String, spec: ApiSpec, apiKey: String, model: String,
        history: List<ChatTurn>, systemPrompt: String, tools: List<ToolDefinition>
    ): Result<RawModelReply> = withContext(Dispatchers.IO) {
        try {
            val url = urlFor(baseUrl, spec, model)
            val body = bodyFor(spec, model, history, systemPrompt, tools)
            val requestBuilder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
            addAuthHeaders(requestBuilder, spec, apiKey)
            val response = client.newCall(requestBuilder.build()).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}: $bodyStr"))
            val reply = when (spec) {
                ApiSpec.ANTHROPIC -> parseAnthropicReply(bodyStr)
                ApiSpec.GOOGLE -> parseGoogleReply(bodyStr)
                ApiSpec.OPENAI -> parseOpenAiReply(bodyStr)
            }
            Result.success(reply)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun streamChat(
        baseUrl: String, spec: ApiSpec, apiKey: String, model: String,
        history: List<ChatTurn>, systemPrompt: String, tools: List<ToolDefinition>,
        onDelta: (String) -> Unit
    ): Result<RawModelReply> = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            // Buffered text produced *this attempt*. Declared out here so the retry decision can see
            // whether anything was already streamed: once the user is watching real text appear, a
            // silent retry would re-stream from the top and duplicate it, so we only ever retry when
            // this attempt produced nothing before it failed.
            val fullText = StringBuilder()
            val openAiToolAcc = LinkedHashMap<Int, ToolAcc>()
            val anthropicToolAcc = LinkedHashMap<Int, ToolAcc>()
            var returnedImageMime: String? = null
            var returnedImageData: String? = null

            val result: Result<RawModelReply> = try {
                val url = urlFor(baseUrl, spec, model)
                val body = bodyFor(spec, model, history, systemPrompt, tools)
                if (spec != ApiSpec.GOOGLE) body.put("stream", true)

                val requestBuilder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
                addAuthHeaders(requestBuilder, spec, apiKey)

                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: ""
                    response.close()
                    // A server-side status: never retried, and typed so the UI keeps it out of the
                    // "network trouble" modal.
                    Result.failure(ApiHttpException(response.code, err))
                } else {
                    val source = response.body?.source()
                    if (source == null) {
                        Result.failure(ApiNetworkException("Empty response body", null))
                    } else {
                        streamBody(spec, source, fullText, openAiToolAcc, anthropicToolAcc,
                            onImage = { m, d -> returnedImageMime = m; returnedImageData = d },
                            onDelta = onDelta)

                        val toolCalls = mutableListOf<ToolCallRequest>()
                        (if (spec == ApiSpec.ANTHROPIC) anthropicToolAcc else openAiToolAcc).forEach { (_, acc) ->
                            if (acc.name.isNotBlank()) {
                                toolCalls.add(ToolCallRequest(acc.id.ifBlank { "call" }, acc.name, acc.args.toString().ifBlank { "{}" }, acc.thoughtSignature))
                            }
                        }
                        Result.success(RawModelReply(fullText.toString(), toolCalls, returnedImageMime, returnedImageData))
                    }
                }
            } catch (e: Exception) {
                // Wrap connectivity faults so the controller can classify them; leave anything else
                // (e.g. a JSON parse error) as-is.
                Result.failure(if (e is java.io.IOException) ApiNetworkException(e.message ?: "network error", e) else e)
            }

            val error = result.exceptionOrNull()
            val retryable = error != null &&
                error !is ApiHttpException &&
                fullText.isEmpty() &&
                isTransientNetwork(error) &&
                attempt < MAX_ATTEMPTS - 1
            if (retryable) {
                kotlinx.coroutines.delay(RETRY_BACKOFF_MS[attempt.coerceAtMost(RETRY_BACKOFF_MS.size - 1)])
                attempt++
                continue
            }
            return@withContext result
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(ApiNetworkException("unreachable", null))
    }

    /**
     * Consume the SSE stream for one attempt, appending text to [fullText], accumulating tool calls,
     * and reporting any generated image. Extracted so [streamChat] can wrap it in a retry loop
     * without the parsing logic bloating the loop body.
     */
    private fun streamBody(
        spec: ApiSpec,
        source: okio.BufferedSource,
        fullText: StringBuilder,
        openAiToolAcc: LinkedHashMap<Int, ToolAcc>,
        anthropicToolAcc: LinkedHashMap<Int, ToolAcc>,
        onImage: (String?, String?) -> Unit,
        onDelta: (String) -> Unit
    ) {
        var returnedImageMime: String? = null
        var returnedImageData: String? = null
        while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val json = try { JSONObject(payload) } catch (e: Exception) { null } ?: continue

                when (spec) {
                    ApiSpec.OPENAI -> {
                        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val piece = delta?.optString("content", "") ?: ""
                        if (piece.isNotEmpty()) { fullText.append(piece); onDelta(piece) }
                        delta?.optJSONArray("tool_calls")?.let { tcArr ->
                            for (i in 0 until tcArr.length()) {
                                val tc = tcArr.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = openAiToolAcc.getOrPut(idx) { ToolAcc() }
                                tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                    acc.args.append(fn.optString("arguments", ""))
                                }
                            }
                        }
                        // Multimodal image output (OpenAI-compatible gateways such as OpenRouter put
                        // generated images in delta.images as data: URLs). Capture the last one so it
                        // can be surfaced as a downloadable attachment on the reply. Not fed to the
                        // typewriter — the base64 must never be typed out as text.
                        imageFromOpenAiImages(delta?.optJSONArray("images"))?.let {
                            returnedImageMime = it.first; returnedImageData = it.second
                        }
                    }
                    ApiSpec.ANTHROPIC -> {
                        when (json.optString("type")) {
                            "content_block_start" -> {
                                val block = json.optJSONObject("content_block")
                                if (block?.optString("type") == "tool_use") {
                                    val idx = json.optInt("index", 0)
                                    anthropicToolAcc[idx] = ToolAcc(id = block.optString("id"), name = block.optString("name"))
                                }
                            }
                            "content_block_delta" -> {
                                val delta = json.optJSONObject("delta")
                                when (delta?.optString("type")) {
                                    "text_delta" -> {
                                        val piece = delta.optString("text", "")
                                        if (piece.isNotEmpty()) { fullText.append(piece); onDelta(piece) }
                                    }
                                    "input_json_delta" -> {
                                        val idx = json.optInt("index", 0)
                                        anthropicToolAcc[idx]?.args?.append(delta.optString("partial_json", ""))
                                    }
                                }
                            }
                        }
                    }
                    ApiSpec.GOOGLE -> {
                        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                val piece = part.optString("text", "")
                                if (piece.isNotEmpty()) { fullText.append(piece); onDelta(piece) }
                                part.optJSONObject("functionCall")?.let { fc ->
                                    val args = fc.optJSONObject("args") ?: JSONObject()
                                    val acc = openAiToolAcc.getOrPut(i) { ToolAcc() }
                                    acc.name = fc.optString("name")
                                    acc.args.append(args.toString())
                                    // The thought signature Gemini's thinking models require sits on the
                                    // PART, next to functionCall — not inside it. Capture it so it can be
                                    // echoed back verbatim in the next turn's history; without it the
                                    // follow-up request 400s and the tool loop never completes. Absent on
                                    // non-thinking models and on the 2nd+ of parallel calls, which is fine.
                                    part.optString("thoughtSignature").takeIf { it.isNotEmpty() }?.let { acc.thoughtSignature = it }
                                }
                                part.optJSONObject("inlineData")?.let { inlineData ->
                                    returnedImageMime = inlineData.optString("mimeType")
                                    returnedImageData = inlineData.optString("data")
                                }
                            }
                        }
                    }
                }
            }
        onImage(returnedImageMime, returnedImageData)
    }

    private fun urlFor(baseUrl: String, spec: ApiSpec, model: String): String = when (spec) {
        ApiSpec.ANTHROPIC -> baseUrl.trimEnd('/') + "/messages"
        ApiSpec.GOOGLE -> baseUrl.trimEnd('/') + "/models/${model.removePrefix("models/")}:streamGenerateContent?alt=sse"
        ApiSpec.OPENAI -> baseUrl.trimEnd('/') + "/chat/completions"
    }

    private fun bodyFor(spec: ApiSpec, model: String, history: List<ChatTurn>, systemPrompt: String, tools: List<ToolDefinition>): JSONObject =
        when (spec) {
            ApiSpec.ANTHROPIC -> buildAnthropicBody(model, history, systemPrompt, tools)
            ApiSpec.GOOGLE -> buildGoogleBody(history, systemPrompt, tools)
            ApiSpec.OPENAI -> buildOpenAiBody(model, history, systemPrompt, tools)
        }

    private fun addAuthHeaders(builder: Request.Builder, spec: ApiSpec, apiKey: String) {
        when (spec) {
            ApiSpec.OPENAI -> builder.addHeader("Authorization", "Bearer $apiKey")
            ApiSpec.ANTHROPIC -> {
                builder.addHeader("x-api-key", apiKey)
                builder.addHeader("anthropic-version", "2023-06-01")
            }
            ApiSpec.GOOGLE -> builder.addHeader("x-goog-api-key", apiKey)
        }
        builder.addHeader("Content-Type", "application/json")
    }

    private fun toolSchema(tools: List<ToolDefinition>): List<JSONObject> {
        return tools.map { t ->
            val props = JSONObject()
            val required = JSONArray()
            for (p in t.params) {
                props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
                if (p.required) required.put(p.name)
            }
            JSONObject()
                .put("_name", t.name)
                .put("_description", t.description)
                .put("_schema", JSONObject().put("type", "object").put("properties", props).put("required", required))
        }
    }

    private fun openAiContent(turn: ChatTurn): Any {
        if (turn.attachmentData != null && turn.attachmentMime?.startsWith("image/") == true) {
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", turn.content))
            arr.put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:${turn.attachmentMime};base64,${turn.attachmentData}")
                )
            )
            return arr
        }
        return turn.content
    }

    private fun anthropicContent(turn: ChatTurn): Any {
        if (turn.attachmentData != null && turn.attachmentMime?.startsWith("image/") == true) {
            val arr = JSONArray()
            arr.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", turn.attachmentMime).put("data", turn.attachmentData)
                )
            )
            arr.put(JSONObject().put("type", "text").put("text", turn.content))
            return arr
        }
        return turn.content
    }

    private fun buildOpenAiBody(model: String, history: List<ChatTurn>, systemPrompt: String, tools: List<ToolDefinition>): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (turn in history) {
            when {
                // The assistant's own tool-call turn: content (possibly empty) plus the tool_calls
                // array, so the model sees the call it made rather than re-issuing it.
                turn.toolCalls.isNotEmpty() -> {
                    val msg = JSONObject().put("role", "assistant")
                    msg.put("content", if (turn.content.isBlank()) JSONObject.NULL else turn.content)
                    val calls = JSONArray()
                    for (c in turn.toolCalls) {
                        calls.put(
                            JSONObject().put("id", c.id).put("type", "function").put(
                                "function",
                                JSONObject().put("name", c.name).put("arguments", c.argumentsJson)
                            )
                        )
                    }
                    msg.put("tool_calls", calls)
                    messages.put(msg)
                }
                // A tool-result turn: one `tool` message per result. Any image the tool surfaced can't
                // ride on a tool message in the OpenAI shape, so it's appended as a following user
                // message so a vision model can still see it.
                turn.toolResults.isNotEmpty() -> {
                    for (r in turn.toolResults) {
                        messages.put(
                            JSONObject().put("role", "tool").put("tool_call_id", r.id).put("content", r.content)
                        )
                    }
                    if (turn.attachmentData != null && turn.attachmentMime?.startsWith("image/") == true) {
                        messages.put(JSONObject().put("role", "user").put("content", openAiContent(turn.copy(toolResults = emptyList()))))
                    }
                }
                else -> messages.put(JSONObject().put("role", turn.role).put("content", openAiContent(turn)))
            }
        }

        val root = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("top_p", TOP_P)
            .put("max_tokens", MAX_TOKENS)
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (t in toolSchema(tools)) {
                toolsArray.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", t.getString("_name"))
                            .put("description", t.getString("_description"))
                            .put("parameters", t.getJSONObject("_schema"))
                    )
                )
            }
            root.put("tools", toolsArray)
        }
        return root
    }

    private fun buildAnthropicBody(model: String, history: List<ChatTurn>, systemPrompt: String, tools: List<ToolDefinition>): JSONObject {
        val messages = JSONArray()
        for (turn in history) {
            when {
                // Assistant tool-call turn → a content array of an optional text block plus one
                // tool_use block per call (input is the parsed argument object).
                turn.toolCalls.isNotEmpty() -> {
                    val content = JSONArray()
                    if (turn.content.isNotBlank()) {
                        content.put(JSONObject().put("type", "text").put("text", turn.content))
                    }
                    for (c in turn.toolCalls) {
                        val input = try { JSONObject(c.argumentsJson) } catch (e: Exception) { JSONObject() }
                        content.put(
                            JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", input)
                        )
                    }
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                }
                // Tool-result turn → a user message whose content is one tool_result block per result;
                // Anthropic requires these to directly follow the matching tool_use. Any surfaced image
                // is added as a trailing image block in the same user message.
                turn.toolResults.isNotEmpty() -> {
                    val content = JSONArray()
                    for (r in turn.toolResults) {
                        content.put(
                            JSONObject().put("type", "tool_result").put("tool_use_id", r.id).put("content", r.content)
                        )
                    }
                    if (turn.attachmentData != null && turn.attachmentMime?.startsWith("image/") == true) {
                        content.put(
                            JSONObject().put("type", "image").put(
                                "source",
                                JSONObject().put("type", "base64").put("media_type", turn.attachmentMime).put("data", turn.attachmentData)
                            )
                        )
                    }
                    messages.put(JSONObject().put("role", "user").put("content", content))
                }
                else -> {
                    val role = if (turn.role == "assistant") "assistant" else "user"
                    messages.put(JSONObject().put("role", role).put("content", anthropicContent(turn)))
                }
            }
        }
        val root = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", TEMPERATURE)
            .put("top_p", TOP_P)
            .put("system", systemPrompt)
            .put("messages", messages)
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (t in toolSchema(tools)) {
                toolsArray.put(
                    JSONObject()
                        .put("name", t.getString("_name"))
                        .put("description", t.getString("_description"))
                        .put("input_schema", t.getJSONObject("_schema"))
                )
            }
            root.put("tools", toolsArray)
        }
        return root
    }

    private fun buildGoogleBody(history: List<ChatTurn>, systemPrompt: String, tools: List<ToolDefinition>): JSONObject {
        val contents = JSONArray()
        for (turn in history) {
            when {
                // Assistant tool-call turn → a `model` turn with an optional text part plus one
                // functionCall part per call (args is the parsed argument object). Gemini re-issues
                // calls if it can't see its own functionCall in the history, so this is what stops the
                // duplicate create.
                turn.toolCalls.isNotEmpty() -> {
                    val parts = JSONArray()
                    if (turn.content.isNotBlank()) parts.put(JSONObject().put("text", turn.content))
                    for (c in turn.toolCalls) {
                        val args = try { JSONObject(c.argumentsJson) } catch (e: Exception) { JSONObject() }
                        val part = JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", args))
                        // Re-attach the thought signature Gemini gave us for this call, on the same part,
                        // exactly as received. This is the fix for the 400 "missing thought_signature"
                        // that killed every tool call on the thinking models: the model can't process the
                        // functionResponse in the next turn unless it sees its own signed reasoning token
                        // back in history. Null on non-thinking models and on parallel-call siblings, in
                        // which case we simply omit the field — which is what the API expects.
                        c.thoughtSignature?.takeIf { it.isNotBlank() }?.let { part.put("thoughtSignature", it) }
                        parts.put(part)
                    }
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                // Tool-result turn → a `user` turn (the REST API only permits user/model roles, and
                // functionResponse is sent under user) with one functionResponse part per result. The
                // response must be an object, so the string result is wrapped as {"result": ...}. Any
                // surfaced image rides along as an inlineData part.
                turn.toolResults.isNotEmpty() -> {
                    val parts = JSONArray()
                    for (r in turn.toolResults) {
                        parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject().put("name", r.name).put("response", JSONObject().put("result", r.content))
                            )
                        )
                    }
                    if (turn.attachmentData != null && turn.attachmentMime?.startsWith("image/") == true) {
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", turn.attachmentMime).put("data", turn.attachmentData)))
                    }
                    contents.put(JSONObject().put("role", "user").put("parts", parts))
                }
                else -> {
                    val role = if (turn.role == "assistant") "model" else "user"
                    val parts = JSONArray()
                    parts.put(JSONObject().put("text", turn.content))
                    if (turn.attachmentData != null && turn.attachmentMime?.startsWith("image/") == true) {
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", turn.attachmentMime).put("data", turn.attachmentData)))
                    }
                    contents.put(JSONObject().put("role", role).put("parts", parts))
                }
            }
        }

        val root = JSONObject().put("contents", contents)
        root.put(
            "generationConfig",
            JSONObject()
                .put("temperature", TEMPERATURE)
                .put("topP", TOP_P)
                .put("maxOutputTokens", MAX_TOKENS)
        )
        if (systemPrompt.isNotBlank()) {
            root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        }
        if (tools.isNotEmpty()) {
            val declarations = JSONArray()
            for (t in toolSchema(tools)) {
                declarations.put(
                    JSONObject()
                        .put("name", t.getString("_name"))
                        .put("description", t.getString("_description"))
                        .put("parameters", t.getJSONObject("_schema"))
                )
            }
            root.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
        }
        return root
    }

    private fun parseOpenAiReply(bodyStr: String): RawModelReply {
        val json = JSONObject(bodyStr)
        val message = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        // isNull(...) is true when "content" is absent or JSON null, so in the else branch the
        // value is a present, non-null string. Using the single-arg optString avoids passing a
        // null default (which JSONObject declares @NonNull) while keeping identical behaviour.
        val text = if (message.isNull("content")) null else message.optString("content")
        val toolCalls = mutableListOf<ToolCallRequest>()
        val toolCallsArray = message.optJSONArray("tool_calls")
        if (toolCallsArray != null) {
            for (i in 0 until toolCallsArray.length()) {
                val tc = toolCallsArray.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                toolCalls.add(ToolCallRequest(tc.optString("id", "call_$i"), fn.getString("name"), fn.optString("arguments", "{}")))
            }
        }
        val image = imageFromOpenAiImages(message.optJSONArray("images"))
        return RawModelReply(text, toolCalls, image?.first, image?.second)
    }

    /**
     * Pull a generated image out of an OpenAI-compatible `images` array (shape:
     * `[{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]`). Returns the last
     * valid (mime, base64) pair, or null if there is no usable image.
     */
    private fun imageFromOpenAiImages(images: JSONArray?): Pair<String, String>? {
        if (images == null) return null
        var found: Pair<String, String>? = null
        for (i in 0 until images.length()) {
            val url = images.optJSONObject(i)?.optJSONObject("image_url")?.optString("url", "") ?: ""
            parseDataUrl(url)?.let { found = it }
        }
        return found
    }

    /** Split a `data:image/png;base64,....` URL into (mime, base64). Null if it isn't a data URL. */
    private fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)           // e.g. "image/png;base64"
        val data = url.substring(comma + 1)
        if (data.isBlank()) return null
        val mime = meta.substringBefore(';').ifBlank { "image/png" }
        return mime to data
    }

    private fun parseAnthropicReply(bodyStr: String): RawModelReply {
        val json = JSONObject(bodyStr)
        val contentArray = json.getJSONArray("content")
        var text: String? = null
        val toolCalls = mutableListOf<ToolCallRequest>()
        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            when (block.optString("type")) {
                "text" -> text = (text ?: "") + block.optString("text")
                "tool_use" -> {
                    val input = block.optJSONObject("input") ?: JSONObject()
                    toolCalls.add(ToolCallRequest(block.optString("id"), block.optString("name"), input.toString()))
                }
            }
        }
        return RawModelReply(text, toolCalls)
    }

    private fun parseGoogleReply(bodyStr: String): RawModelReply {
        val json = JSONObject(bodyStr)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) return RawModelReply(null, emptyList())
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: JSONObject()
        val parts = content.optJSONArray("parts") ?: JSONArray()
        var text: String? = null
        val toolCalls = mutableListOf<ToolCallRequest>()
        var imageMime: String? = null
        var imageData: String? = null
        
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text")) text = (text ?: "") + part.optString("text")
            part.optJSONObject("functionCall")?.let { fc ->
                val args = fc.optJSONObject("args") ?: JSONObject()
                val sig = part.optString("thoughtSignature").takeIf { it.isNotEmpty() }
                toolCalls.add(ToolCallRequest("call_$i", fc.optString("name"), args.toString(), sig))
            }
            part.optJSONObject("inlineData")?.let { inlineData ->
                imageMime = inlineData.optString("mimeType")
                imageData = inlineData.optString("data")
            }
        }
        return RawModelReply(text, toolCalls, imageMime, imageData)
    }
}
