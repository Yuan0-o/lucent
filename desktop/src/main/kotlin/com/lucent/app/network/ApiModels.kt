package com.lucent.app.network

enum class ApiSpec { OPENAI, ANTHROPIC, GOOGLE }

/**
 * One turn of the conversation as sent to the model.
 *
 * Most turns are a plain [role] + [content] (a user message or the assistant's written reply), and
 * an optional image attachment. Two extra, normally-empty fields carry the tool exchange so it can be
 * threaded back to the model in each provider's *native* format:
 *
 *  - [toolCalls] — set on an assistant turn that asked to run one or more tools. Emitted as the
 *    provider's tool-call shape (OpenAI `tool_calls`, Anthropic `tool_use`, Google `functionCall`).
 *  - [toolResults] — set on a "tool" turn carrying the results of those calls. Emitted as the
 *    provider's tool-result shape (OpenAI `tool` messages, Anthropic `tool_result`, Google
 *    `functionResponse`).
 *
 * Why this matters: without it, after the assistant runs a tool the model never sees a record of its
 * own call being made and answered, so it commonly *re-issues the same call* — the "it created the
 * task twice" bug. Feeding the call and its result back in the format the model understands closes
 * that loop, and also keeps the user/model turns correctly alternating (which Google requires).
 */
data class ChatTurn(
    val role: String,
    val content: String,
    val attachmentMime: String? = null,
    val attachmentData: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolResults: List<ToolResultTurn> = emptyList()
)

/** One tool's result, paired to the [ToolCallRequest.id] it answers, for a "tool" [ChatTurn]. */
data class ToolResultTurn(val id: String, val name: String, val content: String)

data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = true)

data class ToolDefinition(val name: String, val description: String, val params: List<ToolParam>)

/**
 * One tool call the model asked to make.
 *
 * [thoughtSignature] is Google-only and normally null. Gemini's thinking-class models (2.5 and, with
 * mandatory enforcement, 3.x) attach an opaque, encrypted `thoughtSignature` string to the *part*
 * that carries a `functionCall`. That signature must be echoed back **verbatim, on the same part**,
 * when the call is threaded into the next request's history — otherwise the API rejects the follow-up
 * turn with `400 INVALID_ARGUMENT: Function call is missing a thought_signature in functionCall parts`
 * and the tool loop dies before the model can answer. For parallel calls in one response only the
 * first part carries a signature, so this is null on the rest; that is expected and handled. The other
 * providers (OpenAI, Anthropic) leave it null and ignore it entirely.
 */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val thoughtSignature: String? = null
)

/** An image pulled out of a note/task attachment so it can be shown to a vision model. */
data class ToolImage(val mime: String, val data: String, val name: String)

/**
 * Result of running one assistant tool. [summary] is the text fed back to the model as the tool
 * result; [images] are any image attachments the tool surfaced (e.g. from read_note/read_task) so
 * the follow-up request can include them as real image content for the model to actually see.
 * [success] is false when the action did not happen (item not found, write failed, attachment
 * still present after a remove, ...). The controller uses it to keep replies honest instead of
 * confirming something that never occurred.
 */
data class ToolExecResult(
    val summary: String,
    val images: List<ToolImage> = emptyList(),
    val success: Boolean = true,
    // The row this tool created or edited, when it maps to one the app can open — how "approve and
    // fine-tune in the editor" lands the user on the exact item (see AssistantConfirmationDialog).
    val openNoteId: Long? = null,
    val openTaskId: Long? = null
)

data class RawModelReply(
    val text: String?,
    val toolCalls: List<ToolCallRequest>,
    val imageMime: String? = null,
    val imageData: String? = null
)

/**
 * The endpoint answered, but with a non-2xx status (bad key, model not found, rate limit, provider
 * error, ...). Deliberately NOT an [java.io.IOException] so the UI can tell a *server-side* failure
 * — which a friendly "check your settings" note fits — apart from a genuine connectivity problem,
 * which gets the dedicated network dialog (issue 19). [code] is the HTTP status; [bodyText] is the
 * provider's error body, trimmed, for the details line.
 */
class ApiHttpException(val code: Int, val bodyText: String) :
    Exception("HTTP $code: ${bodyText.take(500)}")

/**
 * The request never got a usable answer because of the connection itself — no network, DNS failure,
 * timeout, reset, TLS handshake failure. Wraps the underlying cause and, being an [java.io.IOException],
 * is what the controller classifies as "network trouble" and surfaces in the network-error modal.
 */
class ApiNetworkException(message: String, cause: Throwable?) :
    java.io.IOException(message, cause)
