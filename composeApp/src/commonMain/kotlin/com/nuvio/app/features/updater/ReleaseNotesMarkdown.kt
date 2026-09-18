package com.nuvio.app.features.updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

/**
 * Release notes arrive as the GitHub release body, i.e. Markdown. This covers the subset release
 * notes actually use - headings, bullet/numbered lists, bold, italic, strikethrough, inline code,
 * links and bare URLs - and leaves anything it doesn't recognise as literal text.
 */
internal sealed interface ReleaseNoteBlock {
    data class Heading(val level: Int, val text: String) : ReleaseNoteBlock
    data class Paragraph(val text: String) : ReleaseNoteBlock
    data class ListItem(val marker: String, val depth: Int, val text: String) : ReleaseNoteBlock
    data class Code(val text: String) : ReleaseNoteBlock
}

private val headingRegex = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val bulletRegex = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val orderedRegex = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val ruleRegex = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val fenceRegex = Regex("""^\s*(```|~~~)""")
private const val MAX_LIST_DEPTH = 3

internal fun parseReleaseNotes(markdown: String): List<ReleaseNoteBlock> {
    val blocks = mutableListOf<ReleaseNoteBlock>()
    val paragraph = mutableListOf<String>()
    var listItem: ReleaseNoteBlock.ListItem? = null
    var codeLines: MutableList<String>? = null

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += ReleaseNoteBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
        listItem?.let(blocks::add)
        listItem = null
    }

    for (line in markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        val code = codeLines
        if (code != null) {
            if (fenceRegex.containsMatchIn(line)) {
                blocks += ReleaseNoteBlock.Code(code.joinToString("\n"))
                codeLines = null
            } else {
                code += line
            }
            continue
        }
        if (fenceRegex.containsMatchIn(line)) {
            flush()
            codeLines = mutableListOf()
            continue
        }
        if (line.isBlank()) {
            flush()
            continue
        }
        if (ruleRegex.matches(line)) {
            flush()
            continue
        }
        val heading = headingRegex.matchEntire(line.trimStart())
        if (heading != null) {
            flush()
            blocks += ReleaseNoteBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            continue
        }

        val bullet = bulletRegex.matchEntire(line)
        if (bullet != null) {
            flush()
            listItem = ReleaseNoteBlock.ListItem("•", listDepth(bullet.groupValues[1]), bullet.groupValues[2])
            continue
        }
        val ordered = orderedRegex.matchEntire(line)
        if (ordered != null) {
            flush()
            listItem = ReleaseNoteBlock.ListItem(
                marker = "${ordered.groupValues[2]}.",
                depth = listDepth(ordered.groupValues[1]),
                text = ordered.groupValues[3],
            )
            continue
        }

        val currentItem = listItem
        if (currentItem != null) {
            // Wrapped continuation of a list item.
            listItem = currentItem.copy(text = currentItem.text + " " + line.trim())
        } else {
            paragraph += line.trim()
        }
    }
    codeLines?.let { blocks += ReleaseNoteBlock.Code(it.joinToString("\n")) }
    flush()
    return blocks
}

private fun listDepth(indent: String): Int {
    val columns = indent.fold(0) { total, char -> total + if (char == '\t') 4 else 1 }
    return (columns / 2).coerceAtMost(MAX_LIST_DEPTH)
}

internal fun releaseNotesInline(
    text: String,
    linkStyle: SpanStyle,
    codeStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    appendInline(text, linkStyle, codeStyle)
}

private val linkRegex = Regex("""^\[([^\]]+)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
private val bareUrlRegex = Regex("""^https?://[^\s<>()]+""")

private fun AnnotatedString.Builder.appendInline(text: String, linkStyle: SpanStyle, codeStyle: SpanStyle) {
    var index = 0
    while (index < text.length) {
        val rest = text.substring(index)
        val char = text[index]

        if (char == '\\' && index + 1 < text.length && !text[index + 1].isLetterOrDigit()) {
            append(text[index + 1])
            index += 2
            continue
        }
        if (char == '`') {
            val end = text.indexOf('`', index + 1)
            if (end > index + 1) {
                withStyle(codeStyle) { append(text.substring(index + 1, end)) }
                index = end + 1
                continue
            }
        }
        if (char == '[') {
            val match = linkRegex.find(rest)
            if (match != null) {
                withLink(LinkAnnotation.Url(match.groupValues[2], TextLinkStyles(style = linkStyle))) {
                    appendInline(match.groupValues[1], linkStyle, codeStyle)
                }
                index += match.value.length
                continue
            }
        }
        if (char == 'h' && (index == 0 || !text[index - 1].isLetterOrDigit())) {
            val match = bareUrlRegex.find(rest)
            if (match != null) {
                val url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
                withLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle))) { append(url) }
                index += url.length
                continue
            }
        }

        val delimited = matchDelimited(text, index)
        if (delimited != null) {
            val (inner, length, style) = delimited
            withStyle(style) { appendInline(inner, linkStyle, codeStyle) }
            index += length
            continue
        }

        append(char)
        index += 1
    }
}

private val boldStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
private val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
private val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)

/** Returns (inner text, total consumed length, style) for an emphasis span starting at [start]. */
private fun matchDelimited(text: String, start: Int): Triple<String, Int, SpanStyle>? {
    val candidates = listOf("**" to boldStyle, "__" to boldStyle, "~~" to strikeStyle, "*" to italicStyle, "_" to italicStyle)
    for ((delimiter, style) in candidates) {
        if (!text.startsWith(delimiter, start)) continue
        val contentStart = start + delimiter.length
        if (contentStart >= text.length || text[contentStart].isWhitespace()) continue
        // Underscores inside words (snake_case, file_names) are not emphasis.
        if (delimiter[0] == '_' && start > 0 && text[start - 1].isLetterOrDigit()) continue

        var searchFrom = contentStart
        while (true) {
            val end = text.indexOf(delimiter, searchFrom)
            if (end < 0) break
            val valid = end > contentStart &&
                !text[end - 1].isWhitespace() &&
                // A single * or _ must not be half of a ** / __ run.
                (delimiter.length == 2 || text.getOrNull(end + 1) != delimiter[0]) &&
                (delimiter[0] != '_' || text.getOrNull(end + delimiter.length)?.isLetterOrDigit() != true)
            if (valid) {
                return Triple(text.substring(contentStart, end), end + delimiter.length - start, style)
            }
            searchFrom = end + delimiter.length
        }
    }
    return null
}

@Composable
internal fun ReleaseNotesContent(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseReleaseNotes(markdown) }
    val colors = MaterialTheme.colorScheme
    val linkStyle = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant)
    val bodyStyle = MaterialTheme.typography.bodyMedium

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ReleaseNoteBlock.Heading -> Text(
                    text = releaseNotesInline(block.text, linkStyle, codeStyle),
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is ReleaseNoteBlock.Paragraph -> Text(
                    text = releaseNotesInline(block.text, linkStyle, codeStyle),
                    style = bodyStyle,
                    color = colors.onSurfaceVariant,
                )
                is ReleaseNoteBlock.ListItem -> Row(
                    modifier = Modifier.padding(start = (block.depth * 16).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = block.marker,
                        style = bodyStyle,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 12.dp),
                    )
                    Text(
                        text = releaseNotesInline(block.text, linkStyle, codeStyle),
                        style = bodyStyle,
                        color = colors.onSurfaceVariant,
                    )
                }
                is ReleaseNoteBlock.Code -> Text(
                    text = block.text,
                    style = bodyStyle.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
