package com.lucent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small, deliberately incomplete Markdown renderer for note bodies.
 *
 * ### Why hand-rolled, and why this little
 *
 * Pulling in a full CommonMark parser plus a Compose bridge would add a dependency, a transitive
 * tree, and a rendering model that has to be fought into the app's glass-and-gradient styling —
 * all to support syntax nobody writes in a phone note. Tables, footnotes, reference links, HTML
 * blocks: none of it survives contact with a thumb keyboard.
 *
 * What people *do* write is headings, bold, bullets, the occasional bit of `code`, and links. So
 * that is exactly what this handles, in about a page of code with no dependency and complete
 * control over how it looks. Anything it doesn't recognise is simply rendered as the literal text
 * the user typed — which is the correct behaviour for a renderer this size, because a note is not
 * a document that has to compile. Nothing is ever destroyed or hidden; the raw text is always
 * exactly what's in the editor, and the detail page is just a nicer view of it.
 *
 * ### Wiki links
 *
 * `[[Another note]]` is Lucent's own addition and the reason this renderer exists at all rather
 * than a `Text(note.body)`. It renders as a tappable link that opens the note with that title (see
 * [com.lucent.app.data.NoteLinks]), and turns red when it points at nothing — a broken link that
 * looks identical to a working one is worse than no link, so it says so and offers to create the
 * missing note.
 *
 * ### Known limits, stated plainly
 *
 * Inline styles don't nest (`**bold with *italic* inside**` renders the inner asterisks literally),
 * and lists don't support nested indentation levels. Both are fixable and neither is worth the code
 * until someone actually asks.
 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lines: List<String>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
private const val FENCE = "```"

/**
 * Split raw text into blocks. Blank lines end a paragraph; a fenced code block swallows everything
 * verbatim until it closes (or until the text runs out, so an unterminated fence — which is what a
 * half-typed note looks like — still renders sensibly instead of eating the rest of the note).
 */
private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trimEnd())
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        if (line.trimStart().startsWith(FENCE)) {
            flushParagraph()
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(FENCE)) {
                code += lines[i]
                i++
            }
            i++ // step past the closing fence (or past the end, which is fine)
            blocks += MdBlock.Code(code)
            continue
        }

        when {
            line.isBlank() -> flushParagraph()

            RULE.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }

            HEADING.matches(line) -> {
                flushParagraph()
                val m = HEADING.find(line)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
            }

            BULLET.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Bullet(BULLET.find(line)!!.groupValues[1])
            }

            NUMBERED.matches(line) -> {
                flushParagraph()
                val m = NUMBERED.find(line)!!
                blocks += MdBlock.Numbered(m.groupValues[1], m.groupValues[2])
            }

            QUOTE.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Quote(QUOTE.find(line)!!.groupValues[1])
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

// One alternation over every inline form we support. Order matters: [[wiki]] must be tried before
// [text](url), and ** before *, or the shorter form would win and eat half the marker.
private val INLINE = Regex(
    """\[\[([^\[\]]+)]]""" +
        """|\[([^\]]+)]\(([^)\s]+)\)""" +
        """|`([^`]+)`""" +
        """|\*\*([^*]+)\*\*""" +
        """|__([^_]+)__""" +
        """|~~([^~]+)~~""" +
        """|\*([^*\n]+)\*""" +
        """|_([^_\n]+)_"""
)

/**
 * Turn one line of inline Markdown into a styled, link-annotated string.
 *
 * [brokenLinks] is the set of `[[targets]]` that resolve to nothing, matched case-insensitively.
 * Rendering those differently is the difference between a link graph you can trust and one that
 * quietly does nothing when you tap it.
 */
@Composable
private fun inlineAnnotated(
    line: String,
    textColor: Color,
    accent: Color,
    brokenLinks: Set<String>,
    onWikiLink: (String) -> Unit,
    linksEnabled: Boolean
): AnnotatedString {
    val brokenStyle = SpanStyle(color = OverdueColor, textDecoration = TextDecoration.Underline)
    val linkStyle = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)

    return buildAnnotatedString {
        var cursor = 0
        for (match in INLINE.findAll(line)) {
            if (match.range.first > cursor) {
                append(line.substring(cursor, match.range.first))
            }
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> {
                    // [[Wiki link]]. With links turned off (task 3) the target is still shown, but as
                    // plain text with no styling and no tap target — the link "does nothing".
                    val target = g[1].trim()
                    if (!linksEnabled) {
                        append(target)
                    } else {
                        val broken = target.lowercase() in brokenLinks
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "note:$target",
                                styles = TextLinkStyles(style = if (broken) brokenStyle else linkStyle)
                            ) { onWikiLink(target) }
                        ) {
                            append(target)
                        }
                    }
                }
                g[2].isNotEmpty() && g[3].isNotEmpty() -> {
                    // [text](url) — LinkAnnotation.Url is opened by the platform's own UriHandler,
                    // so there's no custom navigation to get wrong and no browser to bundle. With
                    // links off (task 3), just the visible text is shown, unstyled and inert.
                    if (!linksEnabled) {
                        append(g[2])
                    } else {
                        withLink(LinkAnnotation.Url(url = g[3], styles = TextLinkStyles(style = linkStyle))) {
                            append(g[2])
                        }
                    }
                }
                g[4].isNotEmpty() -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = textColor.copy(alpha = 0.10f))
                ) { append(g[4]) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[5]) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[6]) }
                g[7].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[7]) }
                g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[8]) }
                g[9].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[9]) }
            }
            cursor = match.range.last + 1
        }
        if (cursor < line.length) append(line.substring(cursor))
    }
}

/** Just the two link forms, for the links-without-Markdown mode (task 8). */
private val LINKS_ONLY = Regex("""\[\[([^\[\]]+)]]|\[([^\]]+)]\(([^)\s]+)\)""")

/**
 * Render [text] verbatim — no headings, no bold, no code — but with `[[wiki links]]` and
 * `[text](url)` still live and tappable.
 *
 * ### Why this exists
 *
 * Links used to be a sub-toggle of Markdown: turning Markdown off turned links off too, whether you
 * wanted that or not. But the two settings answer completely different questions. Markdown is about
 * *formatting* — do I want `**this**` to render bold or to stay as the asterisks I typed. Links are
 * about *navigation* — is my note graph connected. Tying them together meant anyone who preferred to
 * see their text exactly as written also silently lost every connection between their notes, and got
 * no explanation for it beyond a greyed-out switch.
 *
 * So this is the fourth combination the app was missing: plain text that is still a graph. Everything
 * outside a link is emitted exactly as typed, including any Markdown syntax, which is precisely what
 * "Markdown off" is supposed to mean.
 */
@Composable
fun LinkedPlainText(
    text: String,
    modifier: Modifier = Modifier,
    brokenLinks: Set<String> = emptySet(),
    onWikiLink: (String) -> Unit = {}
) {
    val onGradient = LocalOnGradient.current
    val brokenStyle = SpanStyle(color = OverdueColor, textDecoration = TextDecoration.Underline)
    val linkStyle = SpanStyle(color = onGradient, textDecoration = TextDecoration.Underline)

    val annotated = buildAnnotatedString {
        var cursor = 0
        for (match in LINKS_ONLY.findAll(text)) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> {
                    val target = g[1].trim()
                    val broken = target.lowercase() in brokenLinks
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "note:$target",
                            styles = TextLinkStyles(style = if (broken) brokenStyle else linkStyle)
                        ) { onWikiLink(target) }
                    ) {
                        append(target)
                    }
                }
                g[2].isNotEmpty() && g[3].isNotEmpty() -> {
                    withLink(LinkAnnotation.Url(url = g[3], styles = TextLinkStyles(style = linkStyle))) {
                        append(g[2])
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

    Text(annotated, color = onGradient, modifier = modifier)
}

/**
 * Render [text] as Markdown.
 *
 * [brokenLinks] should hold the lowercased `[[targets]]` that don't resolve; [onWikiLink] is
 * invoked with the raw target when one is tapped (working or broken — the caller decides whether
 * that means "open it" or "offer to create it").
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    brokenLinks: Set<String> = emptySet(),
    onWikiLink: (String) -> Unit = {},
    // Sub-toggle of Markdown (task 3). When false, [[wiki]] and [text](url) render as inert plain
    // text; everything else about Markdown rendering is unchanged. Defaults to true so callers that
    // don't care about links keep the original behaviour.
    linksEnabled: Boolean = true
) {
    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(modifier = Modifier.height(6.dp))
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineAnnotated(block.text, onGradient, onGradient, brokenLinks, onWikiLink, linksEnabled),
                    color = onGradient,
                    fontSize = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 15.sp
                    },
                    fontWeight = FontWeight.SemiBold
                )

                is MdBlock.Paragraph -> Text(
                    inlineAnnotated(block.text, onGradient, onGradient, brokenLinks, onWikiLink, linksEnabled),
                    color = onGradient
                )

                is MdBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•", color = onGradientMuted, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        inlineAnnotated(block.text, onGradient, onGradient, brokenLinks, onWikiLink, linksEnabled),
                        color = onGradient,
                        modifier = Modifier.weight(1f)
                    )
                }

                is MdBlock.Numbered -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("${block.marker}.", color = onGradientMuted, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        inlineAnnotated(block.text, onGradient, onGradient, brokenLinks, onWikiLink, linksEnabled),
                        color = onGradient,
                        modifier = Modifier.weight(1f)
                    )
                }

                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(onGradientMuted)
                    )
                    Text(
                        inlineAnnotated(block.text, onGradientMuted, onGradient, brokenLinks, onWikiLink, linksEnabled),
                        color = onGradientMuted,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                is MdBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(onGradient.copy(alpha = 0.08f))
                        .padding(10.dp)
                ) {
                    // Code is shown verbatim — no inline parsing inside a fence, which is the
                    // entire reason someone typed a fence.
                    Text(
                        block.lines.joinToString("\n"),
                        color = onGradient,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }

                MdBlock.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(onGradientMuted.copy(alpha = 0.4f))
                )
            }
        }
    }
}
