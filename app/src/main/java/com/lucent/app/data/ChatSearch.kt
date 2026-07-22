package com.lucent.app.data

/**
 * Deep, ranked search over the assistant's chat history (issue 3).
 *
 * The old history search only matched a conversation's short auto-title, so a chat about "the
 * dentist" whose title happened to be the first thing the user typed ("hey") was unfindable. This
 * searches the **full text of every message** in a conversation, not just its title, and ranks the
 * results so the most relevant conversation rises to the top.
 *
 * ### Matching model
 * The query is split into whitespace-separated terms and quoted `"exact phrases"`. A conversation
 * matches only when **every** term and phrase appears somewhere in its searchable text (title +
 * concatenated message bodies) — the same AND semantics the notes/tasks search uses, so the box
 * behaves consistently across the app. Matching is case-insensitive and substring-based, which —
 * exactly as argued for [SearchQuery] — is the only approach that keeps working for Chinese,
 * Japanese, and Korean, where no whitespace tokenizer (and therefore no FTS index) can segment
 * words. There is deliberately no separate "semantic"/embedding path: that would mean shipping an
 * on-device embedding model and an index, a large dependency for a local-first notes app, and it
 * would regress CJK the same way FTS does. Substring-over-full-content is the honest "deep search"
 * here — it looks *inside* the conversation, not just at its label.
 *
 * ### Ranking
 * A hit in the title counts for much more than a hit buried in a message, an exact-phrase hit
 * counts for more than a bare term, and more distinct terms matching beats fewer. Ties break on
 * recency (handled by the caller, which keeps the list in most-recent-first order and performs a
 * *stable* sort by score). The result: the conversation actually *about* your query leads, instead
 * of merely the one you happened to open most recently.
 */
object ChatSearch {

    /** One conversation's searchable payload: its title and all of its message text concatenated. */
    data class Doc(val conversationId: Long, val title: String, val content: String)

    private val TOKEN = Regex("\"([^\"]*)\"|(\\S+)")

    private data class ParsedQuery(val terms: List<String>, val phrases: List<String>) {
        val isEmpty get() = terms.isEmpty() && phrases.isEmpty()
    }

    private fun parse(raw: String): ParsedQuery {
        val terms = mutableListOf<String>()
        val phrases = mutableListOf<String>()
        for (m in TOKEN.findAll(raw.trim())) {
            if (m.value.startsWith("\"")) {
                val p = m.groupValues[1].trim().lowercase()
                if (p.isNotEmpty()) phrases += p
            } else {
                val t = m.groupValues[2].trim().lowercase()
                if (t.isNotEmpty()) terms += t
            }
        }
        return ParsedQuery(terms, phrases)
    }

    /**
     * Score one document against the query. Returns 0 when it doesn't match every term/phrase, and
     * a positive relevance score when it does — higher is better.
     */
    fun score(rawQuery: String, doc: Doc): Int = score(parse(rawQuery), doc)

    /**
     * The parsed-query core of [score]. [rank] parses the query ONCE and calls this per document —
     * the public overload above used to be called in its place, which re-ran the tokenizing regex
     * over the query for every conversation in the history on every keystroke.
     */
    private fun score(q: ParsedQuery, doc: Doc): Int {
        if (q.isEmpty) return 0
        val title = doc.title.lowercase()
        val content = doc.content.lowercase()

        var score = 0
        for (phrase in q.phrases) {
            val inTitle = title.contains(phrase)
            val inContent = content.contains(phrase)
            if (!inTitle && !inContent) return 0            // every phrase must appear
            if (inTitle) score += 60
            if (inContent) score += 25
        }
        for (term in q.terms) {
            val inTitle = title.contains(term)
            val inContent = content.contains(term)
            if (!inTitle && !inContent) return 0            // every term must appear
            if (inTitle) score += 30
            if (inContent) score += 10
            // A title that *starts* with the term (or equals it) is almost certainly the one.
            if (title == term) score += 40 else if (title.startsWith(term)) score += 15
        }
        return score
    }

    /**
     * Filter [docs] to those matching [rawQuery] and return their conversation ids ordered best
     * first. A blank query returns null, signalling "no search is active" so the caller shows the
     * full, unfiltered list in its own natural order.
     */
    /**
     * A short preview of the text that made [doc] match [rawQuery] — ported from the second
     * assistant variant, so opening a result is never a shot in the dark. Prefers the first quoted
     * phrase, else the first term, and clips ±[SNIPPET_RADIUS] characters around the hit (which
     * works equally for CJK, where a "word" has no spaces). Falls back to the start of the content.
     */
    fun snippet(rawQuery: String, doc: Doc): String {
        val q = parse(rawQuery)
        val content = doc.content
        if (content.isBlank()) return ""
        val lower = content.lowercase()
        val needle = (q.phrases.firstOrNull { lower.contains(it) }
            ?: q.terms.firstOrNull { lower.contains(it) })
            ?: return content.take(SNIPPET_RADIUS * 2).trim().replace('\n', ' ')
        val at = lower.indexOf(needle)
        val start = (at - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (at + needle.length + SNIPPET_RADIUS).coerceAtMost(content.length)
        val core = content.substring(start, end).replace('\n', ' ').trim()
        return buildString {
            if (start > 0) append('…')
            append(core)
            if (end < content.length) append('…')
        }
    }

    private const val SNIPPET_RADIUS = 42

    fun rank(rawQuery: String, docs: List<Doc>): List<Long>? {
        if (rawQuery.isBlank()) return null
        val q = parse(rawQuery)   // parsed once for the whole pass, not once per conversation
        return docs
            .mapNotNull { d -> score(q, d).takeIf { it > 0 }?.let { d.conversationId to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // ---- Message-level matches (task 9) --------------------------------------------------------
    //
    // The conversation-level search above answers "which chats mention this?". Task 9 needs the
    // finer question "*where* exactly?": every individual place the searched text occurs, so the UI
    // can list each one separately and, on tap, scroll to that message, highlight the hit, and
    // bounce it. So this pass works per message and per occurrence rather than per conversation.
    //
    // The needle is the raw query with surrounding quotes stripped, matched as a contiguous,
    // case-insensitive substring — the most literal reading of "find this content", and (like the
    // rest of the app's search) substring-based so it keeps working for CJK where nothing can
    // tokenise into words. Each hit becomes its own [MessageMatch]; the same message producing three
    // hits yields three entries.

    /** One message's searchable payload. [timestamp] is only used to order results newest-first. */
    data class MessageDoc(
        val conversationId: Long,
        val conversationTitle: String,
        val messageId: Long,
        val content: String,
        val timestamp: Long
    )

    /**
     * A single occurrence of the query inside one message.
     *
     * [matchStart]/[matchLength] locate the hit within the *full* message content, for highlighting
     * the exact run of characters in the chat bubble. [snippet] is a short preview for the results
     * list, with the hit at [hitInSnippetStart]/[hitInSnippetLength] within that preview so the list
     * row can emphasise it too.
     */
    data class MessageMatch(
        val conversationId: Long,
        val conversationTitle: String,
        val messageId: Long,
        val snippet: String,
        val hitInSnippetStart: Int,
        val hitInSnippetLength: Int,
        val matchStart: Int,
        val matchLength: Int
    )

    /** The needle used for message matching: the query, trimmed, with any wrapping quotes removed. */
    fun messageNeedle(rawQuery: String): String = rawQuery.trim().trim('"').trim()

    /**
     * Every occurrence of the query across [docs], newest message first and left-to-right within a
     * message. Empty when the query is blank or nothing matches.
     */
    fun messageMatches(rawQuery: String, docs: List<MessageDoc>): List<MessageMatch> {
        val needle = messageNeedle(rawQuery).lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<MessageMatch>()
        // Newest first so the most recent mentions lead the list; occurrences within a message stay
        // in reading order because we scan each message left to right.
        for (doc in docs.sortedByDescending { it.timestamp }) {
            val content = doc.content
            if (content.isEmpty()) continue
            val lower = content.lowercase()
            var from = 0
            while (true) {
                val at = lower.indexOf(needle, from)
                if (at < 0) break
                val start = (at - SNIPPET_RADIUS).coerceAtLeast(0)
                val end = (at + needle.length + SNIPPET_RADIUS).coerceAtMost(content.length)
                val leadingEllipsis = start > 0
                // Newlines flattened to spaces so the preview stays on one line; replace is
                // char-for-char, so offsets into the snippet remain valid.
                val core = content.substring(start, end).replace('\n', ' ')
                val snippet = buildString {
                    if (leadingEllipsis) append('…')
                    append(core)
                    if (end < content.length) append('…')
                }
                out += MessageMatch(
                    conversationId = doc.conversationId,
                    conversationTitle = doc.conversationTitle,
                    messageId = doc.messageId,
                    snippet = snippet,
                    hitInSnippetStart = (if (leadingEllipsis) 1 else 0) + (at - start),
                    hitInSnippetLength = needle.length,
                    matchStart = at,
                    matchLength = needle.length
                )
                from = at + needle.length   // non-overlapping occurrences
            }
        }
        return out
    }
}
