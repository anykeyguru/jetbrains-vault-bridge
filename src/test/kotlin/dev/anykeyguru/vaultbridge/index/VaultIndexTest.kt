package dev.anykeyguru.vaultbridge.index

import dev.anykeyguru.vaultbridge.parse.NoteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Paths

class VaultIndexTest {
    private fun index(vararg files: Pair<String, String>) =
        VaultIndex(files.map { (path, text) -> NoteParser.parse(path, text) })

    @Test
    fun resolveByNameAliasAndPath() {
        val idx = index(
            "infra/Traefik.md" to "---\naliases: [Reverse Proxy]\n---\n",
            "Harbor.md" to "",
        )
        assertEquals("infra/Traefik.md", idx.resolve("traefik")?.path)
        assertEquals("infra/Traefik.md", idx.resolve("Reverse Proxy")?.path)
        assertEquals("infra/Traefik.md", idx.resolve("infra/Traefik.md")?.path)
        assertEquals("infra/Traefik.md", idx.resolve("Traefik")?.path)
        assertNull(idx.resolve("Missing"))
    }

    @Test
    fun duplicateNamesPreferSameFolderThenShallowest() {
        val idx = index("a/b/Note.md" to "", "c/Note.md" to "", "a/b/From.md" to "")
        assertEquals("a/b/Note.md", idx.resolve("Note", "a/b/From.md")?.path)
        assertEquals("c/Note.md", idx.resolve("Note", "x/Y.md")?.path)
        assertEquals("a/b/Note.md", idx.resolve("b/Note")?.path)
    }

    @Test
    fun backlinksTagsAndUnresolved() {
        val idx = index(
            "A.md" to "[[B]] [[Nowhere]] [[#Self]] #proj/x",
            "B.md" to "#Proj/x",
            "C.md" to "[[B|bee]]",
        )
        assertEquals(listOf("A.md", "C.md"), idx.backlinksTo("B.md").map { it.source.path })
        assertEquals(1, idx.unresolvedLinks)
        assertEquals(listOf("A.md", "B.md"), idx.tags["proj/x"]?.map { it.path })
    }

    @Test
    fun hiddenFoldersSkipped() {
        val root = Paths.get("/v")
        assertEquals(false, VaultIndexService.isVisible(root, Paths.get("/v/.obsidian/x.md")))
        assertEquals(false, VaultIndexService.isVisible(root, Paths.get("/v/.trash/x.md")))
        assertEquals(true, VaultIndexService.isVisible(root, Paths.get("/v/daily/2026.md")))
        assertEquals("daily/2026.md", VaultIndexService.relPath(root, Paths.get("/v/daily/2026.md")))
    }
}
