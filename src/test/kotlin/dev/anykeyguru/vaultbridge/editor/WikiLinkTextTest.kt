package dev.anykeyguru.vaultbridge.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiLinkTextTest {
    private val doc = "x = 1\n# see [[Traefik#Setup|proxy]] and [[Harbor]]\nend"

    @Test
    fun linkAtUsesDocumentOffsets() {
        val start = doc.indexOf("[[Traefik")
        val l = WikiLinkText.linkAt(doc, start + 3)!!
        assertEquals("Traefik", l.target)
        assertEquals("Setup", l.heading)
        assertEquals(start, l.start)
        assertEquals("[[Traefik#Setup|proxy]]", doc.substring(l.start, l.end))
        assertEquals("Harbor", WikiLinkText.linkAt(doc, doc.indexOf("Harbor"))!!.target)
    }

    @Test
    fun linkAtBoundaries() {
        val start = doc.indexOf("[[Harbor")
        val end = start + "[[Harbor]]".length
        assertEquals("Harbor", WikiLinkText.linkAt(doc, start)!!.target)
        assertEquals("Harbor", WikiLinkText.linkAt(doc, end - 1)!!.target)
        assertNull(WikiLinkText.linkAt(doc, end))
        assertNull(WikiLinkText.linkAt(doc, start - 1))
        assertNull(WikiLinkText.linkAt(doc, 2))
        assertNull(WikiLinkText.linkAt("", 0))
    }

    @Test
    fun completionPrefix() {
        val t = "# see [[Tra"
        assertEquals(t.length - 3, WikiLinkText.completionPrefixStart(t, t.length))
        assertEquals(t.length + 5, WikiLinkText.completionPrefixStart("$t]] [[", t.length + 5))
        assertEquals(6, WikiLinkText.completionPrefixStart("// ![[dir/No", 12))
        assertEquals(4, WikiLinkText.completionPrefixStart("# [[a b", 7))
    }

    @Test
    fun noCompletionOutsideOpenLink() {
        assertNull(WikiLinkText.completionPrefixStart("# see [Tra", 10))
        assertNull(WikiLinkText.completionPrefixStart("[[done]] tail", 13))
        assertNull(WikiLinkText.completionPrefixStart("[[Note#Hea", 10))
        assertNull(WikiLinkText.completionPrefixStart("[[Note|ali", 10))
        assertNull(WikiLinkText.completionPrefixStart("[[\nNext", 7))
        assertNull(WikiLinkText.completionPrefixStart("plain", 5))
        assertNull(WikiLinkText.completionPrefixStart("", 0))
    }

    @Test
    fun closingBrackets() {
        assertEquals(2, WikiLinkText.closingBracketsAt("[[a]] x", 3))
        assertEquals(1, WikiLinkText.closingBracketsAt("[[a] x", 3))
        assertEquals(0, WikiLinkText.closingBracketsAt("[[a", 3))
        assertEquals(2, WikiLinkText.closingBracketsAt("[[a]]]", 3))
    }

    @Test
    fun headingOffset() {
        val note = "intro\n```\n# Setup\n```\n## Setup  \ntext\n### Other ###\n"
        assertEquals(note.indexOf("## Setup"), WikiLinkText.headingOffset(note, "setup"))
        assertEquals(note.indexOf("### Other"), WikiLinkText.headingOffset(note, "Other"))
        assertNull(WikiLinkText.headingOffset(note, "Missing"))
        assertNull(WikiLinkText.headingOffset("#NotAHeading\n", "NotAHeading"))
    }

    @Test
    fun attachments() {
        assertTrue(WikiLinkText.isAttachment("img/diagram.PNG"))
        assertTrue(WikiLinkText.isAttachment("spec.pdf"))
        assertFalse(WikiLinkText.isAttachment("v1.2 release"))
        assertFalse(WikiLinkText.isAttachment("Traefik"))
    }

    @Test
    fun validNoteNames() {
        assertTrue(WikiLinkText.isValidNoteName("Traefik"))
        assertTrue(WikiLinkText.isValidNoteName("infra/k8s notes"))
        assertFalse(WikiLinkText.isValidNoteName("../escape"))
        assertFalse(WikiLinkText.isValidNoteName("/abs"))
        assertFalse(WikiLinkText.isValidNoteName("a:b"))
        assertFalse(WikiLinkText.isValidNoteName(".hidden/x"))
        assertFalse(WikiLinkText.isValidNoteName(" "))
    }
}
