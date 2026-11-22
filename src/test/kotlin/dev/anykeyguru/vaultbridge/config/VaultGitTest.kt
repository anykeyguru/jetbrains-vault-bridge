package dev.anykeyguru.vaultbridge.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Работает только с временными папками; пропускается, если `git` нет в PATH. */
class VaultGitTest {
    private lateinit var tmp: Path
    private lateinit var vault: Path
    private val now = LocalDateTime.of(2026, 11, 22, 14, 5)

    @Before
    fun setUp() {
        assumeTrue("git is not on PATH", gitAvailable())
        tmp = Files.createTempDirectory("vb-git").toRealPath()
        vault = Files.createDirectories(tmp.resolve("vault"))
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) tmp.toFile().deleteRecursively()
    }

    private fun sh(dir: Path, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("git ${args.joinToString(" ")}: $out", p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0)
        return out
    }

    private fun initRepo(dir: Path) {
        sh(dir, "init", "-q", "-b", "main")
        configure(dir)
    }

    private fun configure(dir: Path) {
        sh(dir, "config", "user.name", "Test")
        sh(dir, "config", "user.email", "test@example.com")
        sh(dir, "config", "commit.gpgsign", "false")
        sh(dir, "config", "core.hooksPath", tmp.resolve("no-hooks").toString())
    }

    private fun git() = VaultGit(vault, SystemGit)

    @Test
    fun notARepository() {
        assertFalse(git().isWorkTree())
        initRepo(vault)
        assertTrue(git().isWorkTree())
        val sub = Files.createDirectories(vault.resolve("sub"))
        assertTrue(VaultGit(sub, SystemGit).isWorkTree())
    }

    @Test
    fun commitsChangesWithMessageAndCount() {
        initRepo(vault)
        Files.writeString(vault.resolve("a.md"), "a")
        Files.createDirectories(vault.resolve("dir"))
        Files.writeString(vault.resolve("dir/b.md"), "b")
        val r = git().commitIfChanged(now) as CommitResult.Committed
        assertEquals(2, r.files)
        assertEquals("vault: auto-commit 2026-11-22 14:05 (2 files)", r.message)
        assertEquals(r.message, sh(vault, "log", "-1", "--format=%s").trim())
        assertEquals("", sh(vault, "status", "--porcelain"))

        assertEquals(CommitResult.NoChanges, git().commitIfChanged(now))
        assertEquals("1", sh(vault, "rev-list", "--count", "HEAD").trim())

        Files.writeString(vault.resolve("a.md"), "changed")
        val second = git().commitIfChanged(now) as CommitResult.Committed
        assertEquals("vault: auto-commit 2026-11-22 14:05 (1 file)", second.message)
    }

    @Test
    fun vaultInsideBiggerRepoCommitsOnlyVault() {
        initRepo(tmp)
        Files.writeString(tmp.resolve("code.txt"), "outside")
        Files.writeString(vault.resolve("note.md"), "inside")
        val r = git().commitIfChanged(now) as CommitResult.Committed
        assertEquals(1, r.files)
        assertEquals("vault/note.md\n", sh(tmp, "show", "--name-only", "--format=", "HEAD"))
        assertTrue(sh(tmp, "status", "--porcelain").contains("code.txt"))
    }

    @Test
    fun pushToLocalBareRepository() {
        val bare = tmp.resolve("remote.git")
        Files.createDirectories(bare)
        sh(bare, "init", "-q", "--bare")
        initRepo(vault)
        assertEquals(PushResult.Skipped("no remote configured"), git().push())

        sh(vault, "remote", "add", "origin", bare.toString())
        Files.writeString(vault.resolve("a.md"), "a")
        git().commitIfChanged(now)
        assertEquals(PushResult.Pushed, git().push()) // без upstream: push -u origin main
        assertEquals(sh(vault, "rev-parse", "HEAD"), sh(bare, "rev-parse", "main"))
        assertEquals(PushResult.UpToDate, git().push())

        Files.writeString(vault.resolve("b.md"), "b")
        git().commitIfChanged(now)
        assertEquals(PushResult.Pushed, git().push()) // с upstream: git push
        assertEquals(sh(vault, "rev-parse", "HEAD"), sh(bare, "rev-parse", "main"))
    }

    @Test
    fun pushFailureIsReportedOnce() {
        initRepo(vault)
        sh(vault, "remote", "add", "origin", tmp.resolve("does-not-exist.git").toString())
        Files.writeString(vault.resolve("a.md"), "a")
        val pushErrors = mutableListOf<String>()
        val steps = GitSyncSteps(git(), object : GitSyncSteps.Listener {
            override fun pushFailed(message: String) {
                pushErrors += message
            }
        }, clock = { now })
        assertTrue(steps.commit() is CommitResult.Committed)
        assertTrue(steps.push() is PushResult.Failed)
        assertTrue(steps.push() is PushResult.Failed)
        assertEquals(1, pushErrors.size)
        assertTrue(steps.status.lastError!!.startsWith("Push failed"))
        assertTrue(steps.status.lastCommit != null)

        // после успешного пуша следующая ошибка снова уведомит
        val bare = Files.createDirectories(tmp.resolve("does-not-exist.git"))
        sh(bare, "init", "-q", "--bare")
        assertEquals(PushResult.Pushed, steps.push())
        assertEquals(null, steps.status.lastError)
        bare.toFile().deleteRecursively()
        Files.writeString(vault.resolve("b.md"), "b")
        steps.commit()
        assertTrue(steps.push() is PushResult.Failed)
        assertEquals(2, pushErrors.size)
    }

    @Test
    fun initRepositoryWritesGitignoreAndCommits() {
        Files.writeString(vault.resolve("a.md"), "a")
        // идентичность для коммита — через окружение не передать, поэтому сначала init + config
        sh(vault, "init", "-q")
        configure(vault)
        assertEquals(null, git().initRepository())
        assertTrue(Files.readString(vault.resolve(".gitignore")).contains(".obsidian/workspace.json"))
        assertEquals("vault: initial commit", sh(vault, "log", "-1", "--format=%s").trim())

        val other = Files.createDirectories(tmp.resolve("other"))
        Files.writeString(other.resolve(".gitignore"), "mine\n")
        sh(other, "init", "-q")
        configure(other)
        VaultGit(other, SystemGit).initRepository()
        assertEquals("mine\n", Files.readString(other.resolve(".gitignore")))
    }

    @Test
    fun cloneIntoMissingFolder() {
        initRepo(vault)
        Files.writeString(vault.resolve("a.md"), "a")
        git().commitIfChanged(now)
        val target = tmp.resolve("clones/notes")
        val r = VaultGit.clone(SystemGit, vault.toString(), target, Duration.ofSeconds(30))
        assertTrue(r.stderr, r.ok)
        assertTrue(Files.isRegularFile(target.resolve("a.md")))
    }

    companion object {
        fun gitAvailable(): Boolean = try {
            val p = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
