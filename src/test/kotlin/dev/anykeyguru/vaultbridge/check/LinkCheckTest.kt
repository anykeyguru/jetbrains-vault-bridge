package dev.anykeyguru.vaultbridge.check

import dev.anykeyguru.vaultbridge.index.VaultIndex
import dev.anykeyguru.vaultbridge.model.WikiLink
import dev.anykeyguru.vaultbridge.parse.NoteParser
import dev.anykeyguru.vaultbridge.parse.WikiLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCheckTest {
    private val texts = mapOf(
        "infra/Traefik.md" to "---\naliases: [Reverse Proxy]\n---\n# Traefik\n## Middlewares\n### Rate  Limit\n```\n## In code\n```\n",
        "Retry policy.md" to "Intro ^intro-block\n## Backoff\n",
    )
    private val index = VaultIndex(texts.map { (p, t) -> NoteParser.parse(p, t) })
    private var reads = 0
    private val reader: (dev.anykeyguru.vaultbridge.model.Note) -> String? = { reads++; texts[it.path] }

    private fun link(s: String): WikiLink = WikiLinkParser.parse(s).single()
    private fun verdict(s: String) = LinkCheck.classify(link(s), index, reader)

    @Test
    fun okLinks() {
        assertEquals(LinkVerdict.OK, verdict("[[Traefik]]"))
        assertEquals(LinkVerdict.OK, verdict("[[Reverse Proxy]]"))
        assertEquals(LinkVerdict.OK, verdict("[[infra/Traefik|proxy]]"))
        assertEquals(LinkVerdict.OK, verdict("[[Traefik#Middlewares]]"))
        assertEquals(LinkVerdict.OK, verdict("[[traefik#middlewares]]"))
        assertEquals(LinkVerdict.OK, verdict("[[Traefik#rate limit]]"))
        assertEquals(LinkVerdict.OK, verdict("![[Retry policy#Backoff]]"))
    }

    @Test
    fun missingNote() {
        assertEquals(LinkVerdict.MISSING_NOTE, verdict("[[Nginx]]"))
        assertEquals(LinkVerdict.MISSING_NOTE, verdict("[[Nginx#Config]]"))
        assertEquals(LinkVerdict.MISSING_NOTE, verdict("[[other/Traefik]]"))
    }

    @Test
    fun missingHeading() {
        assertEquals(LinkVerdict.MISSING_HEADING, verdict("[[Traefik#Routers]]"))
        // Заголовок внутри блока кода не считается.
        assertEquals(LinkVerdict.MISSING_HEADING, verdict("[[Traefik#In code]]"))
    }

    @Test
    fun nestedHeadingAndBlockRefs() {
        assertEquals(LinkVerdict.OK, verdict("[[Traefik#Traefik#Middlewares]]"))
        assertEquals(LinkVerdict.MISSING_HEADING, verdict("[[Traefik#Traefik#Nope]]"))
        assertEquals(LinkVerdict.OK, verdict("[[Retry policy#^intro-block]]"))
    }

    @Test
    fun attachmentsAndSelfLinksAreSkipped() {
        assertEquals(LinkVerdict.SKIPPED, verdict("[[diagram.png]]"))
        assertEquals(LinkVerdict.SKIPPED, verdict("![[docs/spec.pdf]]"))
        assertEquals(LinkVerdict.SKIPPED, verdict("[[#Heading]]"))
    }

    @Test
    fun unreadableNoteIsNotReportedAndNoTextReadWithoutHeading() {
        assertEquals(LinkVerdict.OK, LinkCheck.classify(link("[[Traefik#Whatever]]"), index) { null })
        reads = 0
        verdict("[[Traefik]]")
        assertEquals(0, reads)
    }

    @Test
    fun headingExistsUsesNavigationNormalisation() {
        assertTrue(LinkCheck.headingExists("# Big   Title  \n", "big title"))
        assertFalse(LinkCheck.headingExists("#NoSpace\n", "NoSpace"))
        assertTrue(LinkCheck.headingExists("text", "^block"))
    }

    @Test
    fun linkTextReasonAndLine() {
        val l = link("x ![[Traefik#Routers|r]]")
        assertEquals("![[Traefik#Routers]]", LinkCheck.linkText(l))
        assertEquals("heading “Routers” not found in Traefik", LinkCheck.reason(LinkVerdict.MISSING_HEADING, l))
        assertEquals("note not found", LinkCheck.reason(LinkVerdict.MISSING_NOTE, l))
        assertEquals(2, LinkCheck.lineOf("a\nb\nc [[x]]", 6))
        assertEquals(0, LinkCheck.lineOf("[[x]]", 0))
    }

    private fun broken(file: String, offset: Int, problem: LinkProblem) =
        BrokenLink("/p/$file", file, offset / 10, offset, "[[X]]", problem, "note not found")

    @Test
    fun groupsAreOrderedAndSorted() {
        val r = LinkCheckResult(
            checked = 10, files = 3,
            broken = listOf(
                broken("b.go", 5, LinkProblem.MISSING_HEADING),
                broken("b.go", 30, LinkProblem.MISSING_NOTE),
                broken("a.py", 50, LinkProblem.MISSING_NOTE),
                broken("a.py", 12, LinkProblem.MISSING_NOTE),
            ),
        )
        val groups = LinkCheck.groups(r)
        assertEquals(listOf(LinkProblem.MISSING_NOTE, LinkProblem.MISSING_HEADING), groups.map { it.first })
        assertEquals(listOf("a.py" to 12, "a.py" to 50, "b.go" to 30), groups[0].second.map { it.fileLabel to it.offset })
        assertEquals("Missing notes", groups[0].first.group)
        assertEquals("Missing headings", groups[1].first.group)
        assertTrue(LinkCheck.groups(LinkCheckResult(1, 1, emptyList())).isEmpty())
        assertEquals(listOf(LinkProblem.MISSING_HEADING), LinkCheck.groups(r.copy(broken = r.broken.take(1))).map { it.first })
    }

    @Test
    fun rowAndSummaries() {
        assertEquals("src/a.py:2  [[X]]  — note not found", LinkCheck.row(broken("src/a.py", 12, LinkProblem.MISSING_NOTE)))
        val r = LinkCheckResult(12, 4, listOf(broken("a", 1, LinkProblem.MISSING_NOTE)))
        assertEquals("12 links checked in 4 files, 1 broken", LinkCheck.summary(r))
        assertEquals("1 link checked in 1 file, 0 broken", LinkCheck.summary(LinkCheckResult(1, 1, emptyList())))
        assertEquals(
            "3 links in 2 files could not be checked: vault folder not found",
            LinkCheck.summary(LinkCheckResult(3, 2, emptyList(), vaultMissing = true, vaultPath = "/v")),
        )
        assertEquals("All 7 note links are OK", LinkCheck.okMessage(LinkCheckResult(7, 3, emptyList())))
        assertEquals("The only note link is OK", LinkCheck.okMessage(LinkCheckResult(1, 3, emptyList())))
        assertEquals("No note links found in 5 project files", LinkCheck.okMessage(LinkCheckResult(0, 5, emptyList())))
    }
}
