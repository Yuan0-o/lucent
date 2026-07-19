package com.lucent.app.data

import com.lucent.app.i18n.S

/**
 * Pure, side-effect-free text statistics for a note (or any string): word count, character counts,
 * line count, and an estimated reading time.
 *
 * The *counting* is pure arithmetic on a String — no Android, no Room — so it is fully unit-testable
 * and can be surfaced wherever it's useful (a note's detail view, the history screen, the assistant)
 * without each caller counting words in its own subtly different way. The small [label] helpers below
 * additionally read the i18n catalog so the unit words ("paragraphs", "characters", …) come out in
 * the active language; the arithmetic they wrap is unchanged.
 *
 * ### Why word counting is script-aware
 *
 * A plain `split on whitespace` is fine for space-delimited languages but wrong for Chinese,
 * Japanese, and Korean, where words are not separated by spaces — an entire paragraph of Chinese
 * would count as a single "word". So CJK characters are counted individually (each is roughly a
 * word's worth of content), while runs of non-CJK letters are counted as whitespace-delimited words.
 * The two are then added. This matches how word counters in mature editors behave and gives a figure
 * that means the same thing across scripts, which is the whole point of showing it.
 */
object NoteStats {

    /** Average reading speed used for the estimate. A commonly cited midpoint for silent reading. */
    private const val WORDS_PER_MINUTE = 200.0

    data class Stats(
        val words: Int,
        val characters: Int,
        val charactersNoSpaces: Int,
        val lines: Int,
        /**
         * Number of paragraphs: runs of non-blank lines separated by one or more blank lines. Any
         * non-empty text is at least one paragraph. This is the figure shown on notes/tasks instead
         * of a word count, because "word" is meaningless in scripts without word separators — a whole
         * paragraph of Chinese is not one word, but it is reliably one paragraph.
         */
        val paragraphs: Int,
        /** Whole minutes to read, rounded up; always at least 1 for any non-empty text. */
        val readingMinutes: Int
    ) {
        val isEmpty: Boolean get() = characters == 0
    }

    /**
     * Count paragraphs as blocks of non-blank lines separated by blank line(s). Empty/blank text is
     * zero paragraphs; any text with content is at least one. Independent of script, so it behaves
     * identically in English, Chinese, or anything else.
     */
    fun paragraphsOf(text: String): Int {
        var count = 0
        var inParagraph = false
        for (line in text.lines()) {
            if (line.isBlank()) {
                inParagraph = false
            } else if (!inParagraph) {
                count++
                inParagraph = true
            }
        }
        return count
    }

    /** True for characters in the main CJK ranges, counted individually rather than by whitespace. */
    private fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF ||   // CJK Unified Ideographs
        cp in 0x3400..0x4DBF ||   // CJK Extension A
        cp in 0x3040..0x309F ||   // Hiragana
        cp in 0x30A0..0x30FF ||   // Katakana
        cp in 0xAC00..0xD7AF ||   // Hangul syllables
        cp in 0xF900..0xFAFF      // CJK Compatibility Ideographs

    fun of(text: String): Stats {
        if (text.isEmpty()) return Stats(0, 0, 0, 0, 0, 0)

        // Count by Unicode code point (ported from the second tasks/notes variant) so an emoji or a
        // CJK-extension ideograph outside the BMP counts as one character, not two UTF-16 halves.
        val characters = text.codePointCount(0, text.length)
        val charactersNoSpaces = characters - text.codePoints().filter { Character.isWhitespace(it) }.count().toInt()
        // A note with content but no newline is still one line; count newlines + 1.
        val lines = text.count { it == '\n' } + 1

        // Walk by code point so surrogate pairs (many CJK-extension and emoji code points) are handled
        // as single characters, not two halves.
        var cjkCount = 0
        var nonCjkWordChars = 0
        var words = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            when {
                isCjk(cp) -> {
                    cjkCount++
                    // A CJK char ends any run of non-CJK letters in progress.
                    if (nonCjkWordChars > 0) { words++; nonCjkWordChars = 0 }
                }
                Character.isLetterOrDigit(cp) -> nonCjkWordChars++
                else -> {
                    // Whitespace or punctuation closes a non-CJK word run.
                    if (nonCjkWordChars > 0) { words++; nonCjkWordChars = 0 }
                }
            }
            i += charCount
        }
        if (nonCjkWordChars > 0) words++
        words += cjkCount

        val readingMinutes = if (words == 0) 0 else Math.ceil(words / WORDS_PER_MINUTE).toInt().coerceAtLeast(1)
        return Stats(
            words = words,
            characters = characters,
            charactersNoSpaces = charactersNoSpaces,
            lines = lines,
            paragraphs = paragraphsOf(text),
            readingMinutes = readingMinutes
        )
    }

    /**
     * The label shown beneath a note/task body: "3 paragraphs · 128 characters" (localized). Replaces
     * the old word-based label, which reported "1 word" for any non-space-separated script (Chinese,
     * Japanese, Korean). Paragraphs and characters both count correctly in every language. Empty
     * string for empty text so the caller shows nothing at all. The unit words come from the i18n
     * catalog, so the label reads naturally in the active language rather than always in English.
     */
    fun paragraphCharLabel(text: String): String {
        val stats = of(text)
        if (stats.isEmpty) return ""
        val paraLabel = if (stats.paragraphs == 1) S.statParagraphsOne else S.statParagraphsN(stats.paragraphs)
        val charLabel = if (stats.characters == 1) S.statCharactersOne else S.statCharactersN(stats.characters)
        return "$paraLabel · $charLabel"
    }

    /** A compact one-line label, e.g. "128 words · 1 min read" (localized). Empty for empty text. */
    fun label(stats: Stats): String {
        if (stats.isEmpty) return ""
        val wordLabel = if (stats.words == 1) S.statWordsOne else S.statWordsN(stats.words)
        return "$wordLabel · ${S.statMinRead(stats.readingMinutes)}"
    }
}
