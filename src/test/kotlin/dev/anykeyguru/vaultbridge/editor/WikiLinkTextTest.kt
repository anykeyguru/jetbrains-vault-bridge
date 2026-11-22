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

class HeadingCompletionTest {
    private fun q(s: String) = WikiLinkText.headingQuery(s.replace("¦", ""), s.indexOf('¦'))

    @org.junit.Test
    fun headingQueryLevels() {
        val one = q("# [[testpath#ins¦")!!
        org.junit.Assert.assertEquals("testpath", one.target)
        org.junit.Assert.assertNull(one.level)
        org.junit.Assert.assertEquals(12, one.hashStart)
        org.junit.Assert.assertEquals(13, one.prefixStart)

        val two = q("# [[testpath##¦")!!
        org.junit.Assert.assertEquals(2, two.level)
        org.junit.Assert.assertEquals(14, two.prefixStart)

        org.junit.Assert.assertEquals(3, q("[[a###x¦")!!.level)
        org.junit.Assert.assertEquals("", q("[[#¦")!!.target)
    }

    @org.junit.Test
    fun noHeadingQueryOutsideOpenLink() {
        org.junit.Assert.assertNull(q("# [[testpath¦"))
        org.junit.Assert.assertNull(q("# [[testpath#x]] #¦"))
        org.junit.Assert.assertNull(q("# [[a|alias#¦"))
        org.junit.Assert.assertNull(q("# [[a#b#¦"))
        org.junit.Assert.assertNull(q("# [testpath#¦"))
    }

    @org.junit.Test
    fun headingsSkipFrontmatterAndCode() {
        val text = "---\ntitle: x\n---\n# Intro\ntext\n## Installation ##\n```\n# not a heading\n```\n### Step [1] | a\n#tag\n"
        org.junit.Assert.assertEquals(
            listOf(Heading(1, "Intro"), Heading(2, "Installation"), Heading(3, "Step [1] | a")),
            WikiLinkText.headings(text),
        )
        org.junit.Assert.assertEquals("Step 1 a", WikiLinkText.headingForLink("Step [1] | a"))
    }
}
