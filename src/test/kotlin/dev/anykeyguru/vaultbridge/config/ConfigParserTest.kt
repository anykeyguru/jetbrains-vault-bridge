package dev.anykeyguru.vaultbridge.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

class ConfigParserTest {
    @Test
    fun commentsAndTrailingCommas() {
        val cfg = ConfigParser.parse(
            """
            {
              "vault": "../notes", // relative
              /* block
                 comment */
              "git": {
                "autoCommit": false,
                "commitInterval": "1h30m",
                "push": true,
                "pushInterval": "2m",
                "remote": "https://example.com/a//b.git", // '//' inside a string stays
              },
            }
            """.trimIndent(),
        )
        assertEquals("../notes", cfg.vault)
        val git = cfg.git!!
        assertFalse(git.autoCommit)
        assertEquals(Duration.ofMinutes(90), git.commitInterval)
        assertTrue(git.push)
        assertEquals(Duration.ofMinutes(2), git.pushInterval)
        assertEquals("https://example.com/a//b.git", git.remote)
    }

    @Test
    fun defaults() {
        val noGit = ConfigParser.parse("""{"vault": "/abs"}""")
        assertNull(noGit.git)
        val git = ConfigParser.parse("""{"vault": "x", "git": {}}""").git!!
        assertTrue(git.autoCommit)
        assertFalse(git.push)
        assertEquals(Duration.ofMinutes(5), git.commitInterval)
        assertEquals(Duration.ofMinutes(30), git.pushInterval)
        assertNull(git.remote)
        assertNull(ConfigParser.parse("{}").vault)
    }

    @Test
    fun minimumsAreClamped() {
        val git = ConfigParser.parse("""{"git": {"commitInterval": "5s", "pushInterval": "10s"}}""").git!!
        assertEquals(Duration.ofSeconds(30), git.commitInterval)
        assertEquals(Duration.ofMinutes(1), git.pushInterval)
    }

    @Test
    fun invalidJson() {
        expectError("""{"vault": "x" "git": {}}""")
        expectError("""{"vault": """)
        expectError("""[1, 2]""")
        expectError("")
        expectError("""{"git": {"push": "yes"}}""", "git.push")
        expectError("""{"git": {"commitInterval": "soon"}}""", "git.commitInterval")
        expectError("""{"vault": 42}""", "vault")
        expectError("""{"git": true}""", "git")
    }

    private fun expectError(text: String, mustContain: String? = null) {
        try {
            ConfigParser.parse(text)
            fail("Expected error for: $text")
        } catch (e: ConfigException) {
            if (mustContain != null) assertTrue(e.message, e.message!!.contains(mustContain))
        }
    }

    @Test
    fun errorKeepsLineNumbers() {
        val text = "{\n  // comment\n  \"vault\": \"x\"\n  \"git\": {}\n}"
        try {
            ConfigParser.parse(text)
            fail()
        } catch (e: ConfigException) {
            assertTrue(e.message, e.message!!.contains("line 4"))
        }
    }

    @Test
    fun pathResolution() {
        val project = Paths.get("/work/proj")
        val home = Paths.get("/Users/me")
        assertEquals(Paths.get("/work/notes"), ConfigParser.resolveVaultPath(project, "../notes", home))
        assertEquals(Paths.get("/work/proj/vault"), ConfigParser.resolveVaultPath(project, "./vault/", home))
        assertEquals(Paths.get("/data/vault"), ConfigParser.resolveVaultPath(project, " /data/vault ", home))
        assertEquals(Paths.get("/Users/me/Notes"), ConfigParser.resolveVaultPath(project, "~/Notes", home))
        assertEquals(home, ConfigParser.resolveVaultPath(project, "~", home))
    }

    @Test
    fun suggestVaultValue() {
        val home = Paths.get("/Users/me")
        assertEquals("../notes", ConfigParser.suggestVaultValue(Paths.get("/Users/me/p/proj"), Paths.get("/Users/me/p/notes"), home))
        assertEquals("vault", ConfigParser.suggestVaultValue(Paths.get("/Users/me/p"), Paths.get("/Users/me/p/vault"), home))
        assertEquals("~/Notes", ConfigParser.suggestVaultValue(Paths.get("/Users/me/a/b/c/d/e"), Paths.get("/Users/me/Notes"), home))
        assertEquals("/srv/notes", ConfigParser.suggestVaultValue(Paths.get("/Users/me/a/b/c/d"), Paths.get("/srv/notes"), home))
    }

    @Test
    fun durations() {
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"))
        assertEquals(Duration.ofMinutes(5), Durations.parse("5m"))
        assertEquals(Duration.ofHours(1), Durations.parse("1H"))
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m"))
        assertEquals(Duration.ofSeconds(3725), Durations.parse("1h 2m 5s"))
        assertEquals(Duration.ofSeconds(90), Durations.parse("90"))
        assertNull(Durations.parse(""))
        assertNull(Durations.parse("5x"))
        assertNull(Durations.parse("5m1h"))
        assertNull(Durations.parse("m"))
        assertEquals("1h30m", Durations.format(Duration.ofMinutes(90)))
        assertEquals("30s", Durations.format(Duration.ofSeconds(30)))
    }

    @Test
    fun loadFromProject() {
        val dir = Files.createTempDirectory("vb-cfg")
        try {
            assertEquals(ConfigLoad.Missing, ConfigParser.load(dir))
            val file = dir.resolve(ConfigParser.FILE_NAME)
            Files.writeString(file, """{ "vault": "../v", }""")
            val loaded = ConfigParser.load(dir) as ConfigLoad.Loaded
            assertEquals(dir.parent.resolve("v").normalize(), loaded.vaultRoot)
            Files.writeString(file, """{ "vault": "" }""")
            assertNull((ConfigParser.load(dir) as ConfigLoad.Loaded).vaultRoot)
            Files.writeString(file, """{ "vault": """)
            assertTrue(ConfigParser.load(dir) is ConfigLoad.Invalid)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun templateParses() {
        val cfg = ConfigParser.parse(ConfigTemplate.render("../notes \"q\""))
        assertEquals("../notes \"q\"", cfg.vault)
        val git = cfg.git!!
        assertTrue(git.autoCommit)
        assertFalse(git.push)
        assertNull(git.remote)
        assertEquals("", ConfigParser.parse(ConfigTemplate.render(null)).vault)
    }

    @Test
    fun excludeFile() {
        val dir = Files.createTempDirectory("vb-excl")
        try {
            val exclude = dir.resolve("info/exclude")
            assertTrue(ExcludeFile.addEntry(exclude, ".vaultbridge.json"))
            assertFalse(ExcludeFile.addEntry(exclude, ".vaultbridge.json"))
            Files.writeString(exclude, "# comment\n*.log")
            assertTrue(ExcludeFile.addEntry(exclude, ".vaultbridge.json"))
            assertEquals("# comment\n*.log\n.vaultbridge.json\n", Files.readString(exclude))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
