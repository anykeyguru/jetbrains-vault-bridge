package dev.anykeyguru.vaultbridge.toolwindow

import dev.anykeyguru.vaultbridge.index.VaultIndex
import dev.anykeyguru.vaultbridge.parse.NoteParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ToolWindowLogicTest {
    private fun index(vararg files: Pair<String, String>) =
        VaultIndex(files.map { (path, text) -> NoteParser.parse(path, text) })

    @Test
    fun tagTreeNestsAndCountsUniqueNotesInSubtree() {
        val idx = index(
            "A.md" to "#project #project/alpha",
            "B.md" to "#Project/alpha/x",
            "C.md" to "#project/beta",
            "D.md" to "#solo",
        )
        val root = TagNode.build(idx.tags)
        assertEquals(listOf("project", "solo"), root.children.keys.toList())
        val project = root.children.getValue("project")
        assertEquals(3, project.count) // A, B, C — A не считается дважды
        assertEquals(listOf("A.md"), project.notes.map { it.path })
        val alpha = project.children.getValue("alpha")
        assertEquals("project/alpha", alpha.fullTag)
        assertEquals(2, alpha.count)
        assertEquals(1, alpha.children.getValue("x").count)
        assertEquals(1, project.children.getValue("beta").count)
        assertEquals(1, root.children.getValue("solo").count)
    }

    @Test
    fun neighbourhoodFollowsLinksAndBacklinksByDepth() {
        // A → B → C → D, E → A, F изолирована
        val idx = index(
            "A.md" to "[[B]] [[Missing]]",
            "B.md" to "[[C]]",
            "C.md" to "[[D]]",
            "D.md" to "",
            "E.md" to "[[A]]",
            "F.md" to "",
        )
        assertEquals(setOf("A.md"), GraphBuilder.neighbourhood(idx, "A.md", 0))
        assertEquals(setOf("A.md", "B.md", "E.md"), GraphBuilder.neighbourhood(idx, "A.md", 1))
        assertEquals(setOf("A.md", "B.md", "C.md", "E.md"), GraphBuilder.neighbourhood(idx, "A.md", 2))
        assertEquals(setOf("B.md", "C.md", "D.md", "A.md"), GraphBuilder.neighbourhood(idx, "C.md", 2))
        assertTrue(GraphBuilder.neighbourhood(idx, "Nope.md", 2).isEmpty())

        val g = GraphBuilder.local(idx, "A.md", 1)
        assertEquals(listOf("A.md", "B.md", "E.md"), g.paths)
        assertEquals(2, g.edgeFrom.size)
        assertEquals(2, g.degree[g.indexOf("A.md")])
    }

    @Test
    fun globalGraphDeduplicatesEdgesAndCaps() {
        val idx = index("A.md" to "[[B]] [[B]] [[A]]", "B.md" to "[[A]]", "C.md" to "[[A]] [[B]]")
        assertEquals(3, GraphBuilder.global(idx).edgeFrom.size) // A-B, A-C, B-C; петля A-A отброшена
        assertEquals(2, GraphBuilder.global(idx, maxEdges = 2).edgeFrom.size)
    }

    @Test
    fun layoutIsDeterministicAndBounded() {
        for (n in listOf(0, 1, 2, 50, 600)) {
            val files = (0 until n).map { i -> "N$i.md" to "[[N${(i * 7 + 1) % maxOf(n, 1)}]] [[N${i / 2}]]" }
            val g = GraphBuilder.global(index(*files.toTypedArray()))
            val a = ForceLayout.layout(g, seed = 7)
            val b = ForceLayout.layout(g, seed = 7)
            assertArrayEquals(a.x, b.x, 0f)
            assertArrayEquals(a.y, b.y, 0f)
            assertEquals(n, a.x.size)
            for (i in 0 until n) {
                assertTrue(a.x[i].isFinite() && a.x[i] in 0f..1f)
                assertTrue(a.y[i].isFinite() && a.y[i] in 0f..1f)
            }
        }
    }

    @Test
    fun layoutPullsLinkedNodesTogether() {
        // две клики по 5 узлов без связей между ними
        val files = (0 until 10).map { i ->
            val base = if (i < 5) 0 else 5
            "N$i.md" to (base until base + 5).filter { it != i }.joinToString(" ") { "[[N$it]]" }
        }
        val g = GraphBuilder.global(index(*files.toTypedArray()))
        val l = ForceLayout.layout(g)
        fun d(a: Int, b: Int) = hypot((l.x[a] - l.x[b]).toDouble(), (l.y[a] - l.y[b]).toDouble())
        val i = { p: String -> g.indexOf(p) }
        val inside = d(i("N0.md"), i("N1.md"))
        val across = d(i("N0.md"), i("N5.md"))
        assertTrue("inside=$inside across=$across", inside < across)
    }

    @Test
    fun relPathAndLinkContext() {
        assertEquals("a/B.md", VaultPanel.relPath("/v/root/", "/v/root/a/B.md"))
        assertEquals("a/B.md", VaultPanel.relPath("C:\\v", "C:/v/a/B.md"))
        assertNull(VaultPanel.relPath("/v/root", "/v/rootX/B.md"))

        val text = "---\ntitle: x\n---\nfirst\n  see [[Target]] here\n"
        val start = text.indexOf("[[")
        val ctx = LinkContext.of(text, start, start + "[[Target]]".length)
        assertEquals(4, ctx.line)
        assertEquals("see ", ctx.before)
        assertEquals("[[Target]]", ctx.link)
        assertEquals(" here", ctx.after)
    }
}
