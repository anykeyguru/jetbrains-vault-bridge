package dev.anykeyguru.vaultbridge.search

import dev.anykeyguru.vaultbridge.parse.NoteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {
    private val traefik = NoteParser.parse("infra/Traefik.md", "---\naliases: [Reverse Proxy]\ntags: [network]\n---\n# Traefik\n")
    private val proxyByTag = NoteParser.parse("misc/Nginx.md", "---\ntags: [proxy]\n---\ntext")
    private val proxyByPath = NoteParser.parse("proxy/Setup.md", "")
    private val proxyByContent = NoteParser.parse("Notes.md", "")
    private val proxyTitle = NoteParser.parse("Proxy.md", "")

    @Test
    fun wordsAreLowercasedAndSplit() {
        assertEquals(listOf("foo", "bar"), SearchMatcher.words("  Foo   BAR "))
        assertTrue(SearchMatcher.words("   ").isEmpty())
    }

    @Test
    fun allWordsMustMatch() {
        assertNotNull(SearchMatcher.match(traefik, listOf("traefik", "network"), null))
        assertNull(SearchMatcher.match(traefik, listOf("traefik", "docker"), null))
        val content = NoteContent("Routes via Docker labels")
        val hit = SearchMatcher.match(traefik, listOf("traefik", "docker"), content)!!
        assertEquals("Routes via Docker labels", hit.snippet)
    }

    @Test
    fun rankingTitleAliasTagPathContent() {
        val words = listOf("proxy")
        val hits = listOf(
            SearchMatcher.match(proxyByContent, words, NoteContent("use a proxy here"))!!,
            SearchMatcher.match(proxyByPath, words, null)!!,
            SearchMatcher.match(proxyByTag, words, null)!!,
            SearchMatcher.match(traefik, words, null)!!,
            SearchMatcher.match(proxyTitle, words, null)!!,
        ).sortedWith(SearchMatcher.ORDER)
        assertEquals(listOf("Proxy.md", "infra/Traefik.md", "misc/Nginx.md", "proxy/Setup.md", "Notes.md"), hits.map { it.note.path })
        assertNull(hits[1].snippet)
        assertNotNull(hits[4].snippet)
    }

    @Test
    fun tagWithHashAndCaseInsensitivity() {
        assertEquals(SearchMatcher.TAG + 0, SearchMatcher.metaWeight(proxyByTag, "#proxy"))
        assertNotNull(SearchMatcher.match(traefik, SearchMatcher.words("REVERSE"), null))
    }

    @Test
    fun snippetIsTrimmedWithEllipsis() {
        val text = "a".repeat(100) + " needle\n\nfound " + "b".repeat(100)
        val s = SearchMatcher.snippet(NoteContent(text), "needle")
        assertTrue(s.startsWith("…") && s.endsWith("…"))
        assertTrue("needle found" in s)
    }
}
