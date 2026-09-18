package com.nuvio.app.features.updater

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.nuvio.app.features.updater.ReleaseNoteBlock.Code
import com.nuvio.app.features.updater.ReleaseNoteBlock.Heading
import com.nuvio.app.features.updater.ReleaseNoteBlock.ListItem
import com.nuvio.app.features.updater.ReleaseNoteBlock.Paragraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesMarkdownTest {

    private val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline)
    private val codeStyle = SpanStyle(background = androidx.compose.ui.graphics.Color.Gray)

    private fun inline(text: String) = releaseNotesInline(text, linkStyle, codeStyle)

    private fun AnnotatedString.styledText(predicate: (SpanStyle) -> Boolean): List<String> =
        spanStyles.filter { predicate(it.item) }.map { text.substring(it.start, it.end) }

    private fun AnnotatedString.bold() = styledText { it.fontWeight == FontWeight.SemiBold }
    private fun AnnotatedString.italic() = styledText { it.fontStyle == FontStyle.Italic }

    @Test
    fun parsesTheFork_0_4_23_2_releaseBody() {
        val body = "- **Player**: seeking forward, back, or to a new spot no longer leaves the picture frozen."
        val blocks = parseReleaseNotes(body)

        val item = blocks.single() as ListItem
        assertEquals("•", item.marker)
        assertEquals(0, item.depth)
        val rendered = inline(item.text)
        assertEquals("Player: seeking forward, back, or to a new spot no longer leaves the picture frozen.", rendered.text)
        assertEquals(listOf("Player"), rendered.bold())
    }

    @Test
    fun parsesTheFork_0_4_23_1_releaseBody() {
        val body = """
            Synced with upstream (now at 0.4.23) and packaged as a new fork build.

            - **Subtitles**: text automatically dims on HDR-capable displays
            - **Trailers**: full playback in fullscreen
            - **Localization**: updated Vietnamese, Greek, Czech, Dutch, Polish, and Indonesian translations
        """.trimIndent()

        val blocks = parseReleaseNotes(body)
        assertEquals(Paragraph("Synced with upstream (now at 0.4.23) and packaged as a new fork build."), blocks[0])
        assertEquals(listOf("Subtitles", "Trailers", "Localization"), blocks.drop(1).map { inline((it as ListItem).text).bold().single() })
    }

    @Test
    fun handlesCrlfHeadingsNumberedAndNestedLists() {
        val blocks = parseReleaseNotes("## What's new ##\r\n1. First\r\n2) Second\r\n  - nested\r\n    * deeper\r\n")

        assertEquals(
            listOf(
                Heading(2, "What's new"),
                ListItem("1.", 0, "First"),
                ListItem("2.", 0, "Second"),
                ListItem("•", 1, "nested"),
                ListItem("•", 2, "deeper"),
            ),
            blocks,
        )
    }

    @Test
    fun joinsWrappedListLinesAndKeepsParagraphLineBreaks() {
        val blocks = parseReleaseNotes("- first line\n  continues here\n\nline one\nline two")

        assertEquals(listOf(ListItem("•", 0, "first line continues here"), Paragraph("line one\nline two")), blocks)
    }

    @Test
    fun skipsRulesAndKeepsFencedCodeVerbatim() {
        val blocks = parseReleaseNotes("Intro\n\n---\n\n```\n**not bold**\n- not a list\n```")

        assertEquals(listOf(Paragraph("Intro"), Code("**not bold**\n- not a list")), blocks)
    }

    @Test
    fun rendersEmphasisVariants() {
        val rendered = inline("a **bold** b __also__ c *it* d _em_ e ~~gone~~")

        assertEquals("a bold b also c it d em e gone", rendered.text)
        assertEquals(listOf("bold", "also"), rendered.bold())
        assertEquals(listOf("it", "em"), rendered.italic())
        assertEquals(listOf("gone"), rendered.styledText { it.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun rendersItalicInsideBold() {
        val rendered = inline("**very *important* note**")

        assertEquals("very important note", rendered.text)
        assertEquals(listOf("very important note"), rendered.bold())
        assertEquals(listOf("important"), rendered.italic())
    }

    @Test
    fun leavesUnmatchedAndIntrawordMarkersAlone() {
        assertEquals("2 * 3 = 6 and **unclosed", inline("2 * 3 = 6 and **unclosed").text)
        val snake = inline("set snake_case_name and a_b")
        assertEquals("set snake_case_name and a_b", snake.text)
        assertTrue(snake.spanStyles.isEmpty())
    }

    @Test
    fun rendersCodeSpansWithoutParsingInside() {
        val rendered = inline("run `./gradlew **build**` now")

        assertEquals("run ./gradlew **build** now", rendered.text)
        assertEquals(listOf("./gradlew **build**"), rendered.styledText { it == codeStyle })
    }

    @Test
    fun rendersMarkdownAndBareLinks() {
        val rendered = inline("See [the **release**](https://example.com/r) or https://example.com/x.")

        assertEquals("See the release or https://example.com/x.", rendered.text)
        val urls = rendered.getLinkAnnotations(0, rendered.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com/r", "https://example.com/x"), urls)
        assertEquals(listOf("release"), rendered.bold())
    }

    @Test
    fun honoursBackslashEscapes() {
        assertEquals("*not italic*", inline("\\*not italic\\*").text)
    }
}
