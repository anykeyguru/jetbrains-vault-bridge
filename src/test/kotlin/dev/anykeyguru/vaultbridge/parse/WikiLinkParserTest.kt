package dev.anykeyguru.vaultbridge.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiLinkParserTest {
    @Test
    fun plainLink() {
        val l = WikiLinkParser.parse("see [[Traefik]] here").single()
        assertEquals("Traefik", l.target)
        assertNull(l.heading)
        assertNull(l.alias)
        assertEquals(4, l.start)
        assertEquals(15, l.end)
    }

    @Test
    fun headingAliasEmbed() {
        val l = WikiLinkParser.parse("![[dir/Note#Setup|how to]]").single()
        assertEquals("dir/Note", l.target)
        assertEquals("Setup", l.heading)
        assertEquals("how to", l.alias)
        assertTrue(l.embed)
    }

    @Test
    fun escapedPipeInTable() {
        val l = WikiLinkParser.parse("| [[Note\\|alias]] |").single()
        assertEquals("Note", l.target)
        assertEquals("alias", l.alias)
    }

    @Test
    fun selfHeadingLinkKeptWithEmptyTarget() {
        val l = WikiLinkParser.parse("[[#Intro]]").single()
        assertEquals("", l.target)
        assertEquals("Intro", l.heading)
    }

    @Test
    fun ignoresBrokenAndEmpty() {
        assertEquals(0, WikiLinkParser.parse("[[]] [[ ]] [[unclosed\nnext]] [single]").size)
    }

    @Test
    fun severalOnOneLine() {
        assertEquals(listOf("a", "b"), WikiLinkParser.parse("// [[a]] and [[b]]").map { it.target })
    }
}
