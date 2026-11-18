package com.laksh.forge.feature.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Just enough Markdown for a chat bubble: headings, bullet and numbered lists, bold, italic
 * and inline code. Models reach for these even when asked to keep formatting light, and a
 * full renderer would be a dependency for four constructs.
 */
@Immutable
sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String, val depth: Int) : MdBlock
}

object CoachMarkdown {

    private val headingRe = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
    private val bulletRe = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val numberedRe = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val ruleRe = Regex("""^\s*([-*_])\s*(?:\1\s*){2,}$""")
    private val fenceRe = Regex("""^\s*```.*$""")

    fun parse(src: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
        for (raw in src.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            if (line.isBlank() || ruleRe.matches(line) || fenceRe.matches(line)) {
                flush()
                continue
            }
            val heading = headingRe.matchEntire(line)
            val bullet = if (heading == null) bulletRe.matchEntire(line) else null
            val numbered = if (heading == null && bullet == null) numberedRe.matchEntire(line) else null
            when {
                heading != null -> {
                    flush()
                    blocks.add(MdBlock.Heading(heading.groupValues[1]))
                }
                bullet != null -> {
                    flush()
                    blocks.add(MdBlock.ListItem("•", bullet.groupValues[2], depthOf(bullet.groupValues[1])))
                }
                numbered != null -> {
                    flush()
                    blocks.add(
                        MdBlock.ListItem("${numbered.groupValues[2]}.", numbered.groupValues[3], depthOf(numbered.groupValues[1]))
                    )
                }
                else -> {
                    // Strip table pipes so a stray table still reads as text.
                    val text = if (line.trimStart().startsWith("|")) {
                        line.trim().trim('|').replace("|", " · ")
                    } else line
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(text.trim())
                }
            }
        }
        flush()
        return blocks
    }

    private fun depthOf(indent: String): Int = (indent.replace("\t", "    ").length / 2).coerceIn(0, 3)

    /**
     * Inline styles. An unclosed `**` styles to the end of the text, so bold appears as it
     * streams in rather than flashing raw asterisks; an unmatched single `*` stays literal.
     */
    fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        var bold = false
        var italic = false
        val run = StringBuilder()
        fun flushRun() {
            if (run.isEmpty()) return
            val style = SpanStyle(
                fontWeight = if (bold) FontWeight.SemiBold else null,
                fontStyle = if (italic) FontStyle.Italic else null
            )
            withStyle(style) { append(run.toString()) }
            run.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "*`_#" -> {
                    run.append(text[i + 1]); i += 2
                }
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    flushRun(); bold = !bold; i += 2
                }
                c == '*' && (italic || opensItalic(text, i)) -> {
                    flushRun(); italic = !italic; i += 1
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flushRun()
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        // Unclosed while streaming: hide the tick, show the text plainly.
                        i += 1
                    }
                }
                else -> {
                    run.append(c); i += 1
                }
            }
        }
        flushRun()
    }

    /** A single `*` opens italics only if it hugs a word and a closing `*` follows. */
    private fun opensItalic(text: String, i: Int): Boolean {
        val next = text.getOrNull(i + 1) ?: return false
        if (next.isWhitespace()) return false
        var j = i + 1
        while (j < text.length) {
            if (text[j] == '*') {
                val doubled = text.getOrNull(j + 1) == '*' || text.getOrNull(j - 1) == '*'
                if (!doubled && !text[j - 1].isWhitespace()) return true
                if (doubled) { j += 2; continue }
            }
            j++
        }
        return false
    }
}

/** Renders [text] as styled blocks. Parsing is cheap but still skipped when text is unchanged. */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val blocks = remember(text) { CoachMarkdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = remember(block, codeBg) { CoachMarkdown.inline(block.text, codeBg) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                is MdBlock.Paragraph -> Text(
                    text = remember(block, codeBg) { CoachMarkdown.inline(block.text, codeBg) },
                    style = style,
                    color = color
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 14).dp)) {
                    Text(
                        block.marker,
                        style = style,
                        color = color.copy(alpha = 0.75f),
                        modifier = Modifier.width(if (block.marker == "•") 14.dp else 22.dp)
                    )
                    Text(
                        text = remember(block, codeBg) { CoachMarkdown.inline(block.text, codeBg) },
                        style = style,
                        color = color,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
