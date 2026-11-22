package dev.anykeyguru.vaultbridge.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteNamesTest {
    @Test
    fun sanitize() {
        assertEquals("Idea.md", NoteNames.toRelativePath("  Idea  "))
        assertEquals("Idea.md", NoteNames.toRelativePath("Idea.md"))
        assertEquals("projects/infra/Traefik.md", NoteNames.toRelativePath("projects/ infra //Traefik"))
        assertEquals("a/b.md", NoteNames.toRelativePath("a\\b"))
        assertEquals("What-.md", NoteNames.toRelativePath("What?"))
        assertEquals("x/A- B-.md", NoteNames.toRelativePath("../x/A: B|"))
        assertEquals("hidden.md", NoteNames.toRelativePath(".hidden"))
        assertEquals("/abs/x.md".removePrefix("/"), NoteNames.toRelativePath("/abs/x"))
        assertNull(NoteNames.toRelativePath("   "))
        assertNull(NoteNames.toRelativePath("../.."))
        assertEquals("Traefik", NoteNames.title("projects/infra/Traefik.md"))
    }
}
