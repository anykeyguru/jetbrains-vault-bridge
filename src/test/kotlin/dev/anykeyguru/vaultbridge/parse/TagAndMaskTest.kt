package dev.anykeyguru.vaultbridge.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class TagAndMaskTest {
    @Test
    fun inlineTags() {
        val text = "#todo first\n# Heading\ntext #project/alpha, (#x) url.com/#anchor #2026 #v2"
        assertEquals(listOf("todo", "project/alpha", "x", "v2"), TagParser.parse(text))
    }

    @Test
    fun codeIsMaskedWithSameLength() {
        val text = "a [[real]]\n```\n[[fake]] #fake\n```\n`[[inline]]` #ok"
        val masked = MarkdownMask.maskCode(text)
        assertEquals(text.length, masked.length)
        assertEquals(listOf("real"), WikiLinkParser.parse(masked).map { it.target })
        assertEquals(listOf("ok"), TagParser.parse(masked))
    }

    @Test
    fun noteParserCombinesEverything() {
        val text = "---\ntags: [infra]\naliases: [TR]\n---\n[[Other]] #Infra #new\n```\n[[Hidden]]\n```"
        val n = NoteParser.parse("dir/Traefik.md", text)
        assertEquals("Traefik", n.name)
        assertEquals("Traefik", n.title)
        assertEquals("dir", n.folder)
        assertEquals(listOf("TR"), n.aliases)
        assertEquals(listOf("infra", "new"), n.tags) // #Infra — дубль infra без учёта регистра
        assertEquals(listOf("Other"), n.links.map { it.target })
    }
}
