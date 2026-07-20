package com.lucent.app.data

/**
 * A cheap, provider-agnostic estimate of how many tokens a piece of text costs — shown under every
 * assistant reply (issue 9) and used to reason about the cost of each memory tier.
 *
 * ### Why an estimate and not the real number
 * The exact token count is defined by the model's own tokenizer (BPE tables that differ between
 * OpenAI, Anthropic, and Google), and providers only report it in a `usage` field that not every
 * OpenAI-compatible gateway returns — and never at all when the response is streamed the way this
 * app streams it. Shipping a real tokenizer would mean bundling a large vocabulary per provider for
 * a number that is, by its nature, only ever a rough guide to cost. So we approximate, and label it
 * as approximate in the UI.
 *
 * ### The heuristic
 * Two rules, blended, because one number has to be reasonable across wildly different scripts:
 *  - CJK / full-width characters tend to be **one token each** (often more). We count them 1:1.
 *  - Alphabetic scripts average out near **~4 characters per token** in every major tokenizer.
 *
 * So the estimate is `cjkChars + ceil(otherChars / 4)`, with a floor of 1 for any non-empty text.
 * It won't match a provider's bill exactly, but it tracks it closely enough to tell a one-line
 * reply from a wall of text, and to show that raising the memory tier raises the cost — which is
 * the entire point of surfacing it.
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4.0

    /** Approximate token count of a single string. */
    fun estimate(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var cjk = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isWideScript(cp)) cjk++ else if (!Character.isWhitespace(cp)) other++
        }
        val est = cjk + Math.ceil(other / CHARS_PER_TOKEN).toInt()
        return if (est <= 0 && text.isNotBlank()) 1 else est
    }

    /** Sum of the estimates of several strings (e.g. every message that goes into one request). */
    fun estimateAll(texts: Iterable<String?>): Int = texts.sumOf { estimate(it) }

    /**
     * A short, human label for a token count, e.g. "~312 tokens" or "~1.2k tokens". Kept tiny on
     * purpose — it lives in a muted footnote under the bubble, not a headline.
     */
    fun label(tokens: Int): String {
        if (tokens <= 0) return "~0 tokens"
        return if (tokens >= 1000) {
            val k = tokens / 1000.0
            "~${String.format("%.1f", k)}k tokens"
        } else {
            "~$tokens tokens"
        }
    }

    /**
     * The scripts that behave like "one glyph ≈ one token": CJK ideographs, kana, Hangul, and the
     * CJK symbol/full-width ranges. Everything else falls through to the characters-per-token path.
     * Kept aligned with the no-space-script test used by the typewriter so the two never disagree
     * about what counts as a dense script.
     */
    private fun isWideScript(cp: Int): Boolean {
        return (cp in 0x3040..0x30FF) ||   // Hiragana + Katakana
            (cp in 0x3400..0x4DBF) ||       // CJK Ext A
            (cp in 0x4E00..0x9FFF) ||       // CJK Unified
            (cp in 0xAC00..0xD7AF) ||       // Hangul syllables
            (cp in 0xF900..0xFAFF) ||       // CJK compatibility ideographs
            (cp in 0x3000..0x303F) ||       // CJK symbols & punctuation
            (cp in 0xFF00..0xFFEF) ||       // Full-width forms
            (cp in 0x20000..0x2FA1F)        // CJK Ext B..F (supplementary)
    }
}
