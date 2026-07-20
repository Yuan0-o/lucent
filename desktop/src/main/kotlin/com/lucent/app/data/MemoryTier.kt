package com.lucent.app.data

/**
 * How much prior conversation the assistant is fed on each turn — the "Memory & Cost Management"
 * setting (issue 9).
 *
 * The tier is purely a decision about *how much history to send in the request*, made in
 * [com.lucent.app.ui.AssistantController.send]. It changes nothing about how messages are stored:
 * every message the user sends and every reply is always saved to the database regardless of tier,
 * so lowering the tier never deletes anything and raising it later immediately has more context to
 * draw on. It only ever decides which of those stored messages travel to the model this turn, which
 * is exactly what drives the token bill.
 *
 *  - [LOW]    — single-turn. Only the message the user just sent is sent to the model. Cheapest by
 *               far: the request never grows with the conversation, so every turn costs about the
 *               same handful of tokens. The trade is that the assistant has no memory — it can't
 *               refer back to anything said earlier in the thread.
 *  - [MEDIUM] — the current conversation. The whole active thread is sent, so the assistant
 *               remembers everything in *this* conversation. Cost grows with the length of the
 *               thread. This is the default and matches how the app behaved before the setting
 *               existed.
 *  - [HIGH]   — cross-conversation. Recent messages from *other* conversations are sent as well,
 *               up to a bounded window, so the assistant carries context across separate chats.
 *               Most expensive, because each turn also pays for that shared history; the window is
 *               capped ([HIGH_CROSS_MESSAGE_BUDGET]) so it can never grow without limit.
 */
enum class MemoryTier(val key: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        val DEFAULT = MEDIUM

        /**
         * How many messages of *other-conversation* history the HIGH tier is allowed to attach,
         * newest first. A hard cap so global memory can never turn one request into the entire
         * chat archive — that would be both ruinously expensive and slower than it is useful.
         */
        const val HIGH_CROSS_MESSAGE_BUDGET = 40

        fun fromKey(key: String?): MemoryTier =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
