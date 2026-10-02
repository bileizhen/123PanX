package io.github.bileizhen.pan123x.feature.about

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class MarkdownTextTest {
    @Test fun allHeadingLevelsLoseTheirMarkers() {
        val blocks = markdownBlocks((1..6).joinToString("\n") { "${"#".repeat(it)} Title $it" })
        assertEquals((1..6).toList(), blocks.map { it.level })
        assertTrue(blocks.all { it.kind == MarkdownKind.HEADING && !it.text.startsWith("#") })
    }
    @Test fun listsRetainBulletAndOrderedStructure() {
        val blocks = markdownBlocks("- first\n* second\n+ third\n1. fourth\n2) fifth")
        assertEquals(listOf(MarkdownKind.BULLET, MarkdownKind.BULLET, MarkdownKind.BULLET, MarkdownKind.ORDERED, MarkdownKind.ORDERED), blocks.map { it.kind })
        assertEquals(listOf("1", "2"), blocks.takeLast(2).map { it.number })
        assertEquals("first", blocks.first().text)
    }
    @Test fun codeBlocksKeepIndentationAndMarkdownLiteral() {
        val blocks = markdownBlocks("```kotlin\n  **literal**\n  val x = 1\n```\n> quotation\n---")
        assertEquals(MarkdownBlock(MarkdownKind.CODE, "  **literal**\n  val x = 1"), blocks[0])
        assertEquals(MarkdownKind.QUOTE, blocks[1].kind)
        assertEquals(MarkdownKind.RULE, blocks[2].kind)
    }
    @Test fun incompleteCodeFenceStillShowsContent() {
        assertEquals(listOf(MarkdownBlock(MarkdownKind.CODE, "code")), markdownBlocks("~~~\ncode"))
    }
    @Test fun inlineFormattingRemovesMarkersAndPreservesStyles() {
        val text = inlineMarkdown("**bold** *italic* _other_ `code`", SpanStyle()) {}
        assertEquals("bold italic other code", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        assertEquals(2, text.spanStyles.count { it.item.fontStyle == FontStyle.Italic })
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }
    @Test fun codeSpansKeepMarkdownCharactersLiteral() {
        assertEquals("**literal**", inlineMarkdown("`**literal**`", SpanStyle()) {}.text)
    }
    @Test fun webLinksAreClickableWithoutExposingTheirMarkdownSyntax() {
        var clicked: String? = null
        val text = inlineMarkdown("[GitHub](https://github.com/bileizhen/123PanX)", SpanStyle()) { clicked = it }
        assertEquals("GitHub", text.text)
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Clickable
        link.linkInteractionListener!!.onClick(link)
        assertEquals("https://github.com/bileizhen/123PanX", clicked)
    }
    @Test fun nonWebLinksRemainPlainLabels() {
        for (url in listOf("javascript:alert", "file:///data/private", "intent://install", "https://user:secret@example.com")) {
            val text = inlineMarkdown("[label]($url)", SpanStyle()) { error("Should not be clickable") }
            assertEquals("label", text.text)
            assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        }
    }
    @Test fun repeatedReleaseHeadingsAreRemovedWithoutRemovingOtherVersions() {
        assertEquals("### Fixes\nbody\n## 0.4.20", stripVersionHeadings("0.4.2", "## 0.4.2\n### Fixes\nbody\n# v0.4.2\n## 0.4.20"))
    }
    @Test fun plainAndUnknownSyntaxRemainReadable() {
        assertEquals("raw **unfinished", inlineMarkdown("raw **unfinished", SpanStyle()) {}.text)
        assertEquals(listOf(MarkdownBlock(MarkdownKind.TEXT, "message")), markdownBlocks("message\r\n"))
        assertEquals("## header", stripVersionHeadings("", "## header"))
    }
}
