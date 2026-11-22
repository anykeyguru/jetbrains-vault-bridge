package dev.anykeyguru.vaultbridge.config

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class GitResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** Хвост stderr (или stdout) для уведомления. */
    fun errorTail(maxLines: Int = 6): String {
        if (timedOut) return "git timed out (no network, VPN or a credentials prompt?)"
        val text = stderr.ifBlank { stdout }.trim()
        return text.lines().filter { it.isNotBlank() }.takeLast(maxLines).joinToString("\n")
            .ifEmpty { "git exited with code $exitCode" }
    }
}

fun interface GitRunner {
    fun run(dir: Path, args: List<String>, timeout: Duration): GitResult
}

/**
 * Системный `git` через GeneralCommandLine: окружение как у консоли (ssh-agent, PATH из shell),
 * `GIT_TERMINAL_PROMPT=0` и закрытый stdin, чтобы никогда не ждать ввода пароля. Только не на EDT.
 */
object SystemGit : GitRunner {
    override fun run(dir: Path, args: List<String>, timeout: Duration): GitResult = try {
        val cmd = GeneralCommandLine("git")
            .withParameters(args)
            .withWorkDirectory(dir.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("GIT_TERMINAL_PROMPT", "0")
            .withCharset(Charsets.UTF_8)
        val handler = CapturingProcessHandler(cmd)
        runCatching { handler.processInput.close() }
        val out = handler.runProcess(timeout.toMillis().toInt(), true)
        GitResult(out.exitCode, out.stdout, out.stderr, out.isTimeout)
    } catch (e: Exception) {
        GitResult(-1, "", "Cannot run git: ${e.message ?: e}")
    }
}

sealed interface CommitResult {
    data object NoChanges : CommitResult
    data class Committed(val files: Int, val message: String) : CommitResult
    data class Failed(val message: String) : CommitResult
}

sealed interface PushResult {
    data class Skipped(val reason: String) : PushResult
    data object UpToDate : PushResult
    data object Pushed : PushResult
    data class Failed(val message: String) : PushResult
}

/** Git-операции над папкой vault. Все методы блокирующие. */
class VaultGit(
    val dir: Path,
    private val runner: GitRunner = SystemGit,
    private val shortTimeout: Duration = Duration.ofSeconds(20),
    private val longTimeout: Duration = Duration.ofSeconds(60),
) {
    private fun git(vararg args: String, timeout: Duration = shortTimeout) = runner.run(dir, args.toList(), timeout)

    /** Vault — рабочее дерево git (сам корень репозитория или папка внутри него). */
    fun isWorkTree(): Boolean {
        if (!Files.isDirectory(dir)) return false
        val r = git("rev-parse", "--is-inside-work-tree")
        return r.ok && r.stdout.trim() == "true"
    }

    /**
     * `status --porcelain` → если есть изменения, `add -A` и `commit` — только в пределах папки vault
     * (pathspec `.`), чтобы не захватить чужие изменения, если vault лежит внутри большего репозитория.
     * Хуки пользователя выполняются (никакого `--no-verify`).
     */
    fun commitIfChanged(now: LocalDateTime = LocalDateTime.now()): CommitResult {
        val status = git("status", "--porcelain", "--", ".")
        if (!status.ok) return CommitResult.Failed(status.errorTail())
        if (status.stdout.isBlank()) return CommitResult.NoChanges
        val add = git("add", "-A", "--", ".", timeout = longTimeout)
        if (!add.ok) return CommitResult.Failed(add.errorTail())
        val staged = git("diff", "--cached", "--name-only", "-z", "--", ".")
        if (!staged.ok) return CommitResult.Failed(staged.errorTail())
        val count = staged.stdout.split('\u0000').count { it.isNotBlank() }
        if (count == 0) return CommitResult.NoChanges
        val message = commitMessage(now, count)
        val commit = git("commit", "-q", "-m", message, "--", ".", timeout = longTimeout)
        if (!commit.ok) return CommitResult.Failed(commit.errorTail())
        return CommitResult.Committed(count, message)
    }

    /**
     * `git push`, только если у репозитория есть remote. Без upstream — `push -u origin <branch>`,
     * если есть remote `origin`. Никаких новых remotes и никакого force.
     */
    fun push(): PushResult {
        val remotes = git("remote")
        if (!remotes.ok) return PushResult.Failed(remotes.errorTail())
        val names = remotes.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return PushResult.Skipped("no remote configured")
        val branch = git("symbolic-ref", "--short", "-q", "HEAD")
        if (!branch.ok || branch.stdout.isBlank()) return PushResult.Skipped("detached HEAD")
        val branchName = branch.stdout.trim()
        val upstream = git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        val result = if (upstream.ok && upstream.stdout.isNotBlank()) {
            val ahead = git("rev-list", "--count", "@{u}..HEAD")
            if (ahead.ok && ahead.stdout.trim() == "0") return PushResult.UpToDate
            git("push", timeout = longTimeout)
        } else if ("origin" in names) {
            git("push", "-u", "origin", branchName, timeout = longTimeout)
        } else {
            return PushResult.Failed("Branch $branchName has no upstream and there is no remote named origin")
        }
        return if (result.ok) PushResult.Pushed else PushResult.Failed(result.errorTail())
    }

    /** `git init` + `.gitignore` (если его нет) + первый коммит. Возвращает текст ошибки или null. */
    fun initRepository(): String? {
        val init = git("init")
        if (!init.ok) return init.errorTail()
        val ignore = dir.resolve(".gitignore")
        if (!Files.exists(ignore)) Files.writeString(ignore, DEFAULT_GITIGNORE)
        val add = git("add", "-A", timeout = longTimeout)
        if (!add.ok) return add.errorTail()
        val commit = git("commit", "-q", "-m", "vault: initial commit", timeout = longTimeout)
        return if (commit.ok) null else commit.errorTail()
    }

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val DEFAULT_GITIGNORE = """
            |# Obsidian: per-device UI state and caches
            |.obsidian/workspace.json
            |.obsidian/workspace-mobile.json
            |.obsidian/cache
            |.trash/
            |.DS_Store
            |""".trimMargin()

        fun commitMessage(now: LocalDateTime, files: Int): String =
            "vault: auto-commit ${now.format(STAMP)} ($files ${if (files == 1) "file" else "files"})"

        fun clone(runner: GitRunner, remote: String, target: Path, timeout: Duration = Duration.ofMinutes(10)): GitResult {
            val parent = target.toAbsolutePath().parent
            Files.createDirectories(parent)
            return runner.run(parent, listOf("clone", remote, target.toAbsolutePath().toString()), timeout)
        }
    }
}

/** Последние события синхронизации — для диагностики и статус-бара. */
data class GitSyncStatus(
    val repoDetected: Boolean = false,
    val autoCommit: Boolean = false,
    val push: Boolean = false,
    val lastCommit: Instant? = null,
    val lastCommitFiles: Int = 0,
    val lastPush: Instant? = null,
    val lastError: String? = null,
)

/**
 * Шаги коммита/пуша + правило «одно уведомление об ошибке, пока не будет успеха».
 * Без платформы: сервис оборачивает это расписанием и уведомлениями, тесты — фейковым listener.
 */
class GitSyncSteps(
    private val git: VaultGit,
    private val listener: Listener,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    interface Listener {
        fun commitFailed(message: String) {}
        fun pushFailed(message: String) {}
        fun statusChanged(status: GitSyncStatus) {}
    }

    @Volatile
    var status = GitSyncStatus(repoDetected = true)
        private set
    private var commitFailing = false
    private var pushFailing = false

    fun commit(alwaysReport: Boolean = false): CommitResult {
        val r = git.commitIfChanged(clock())
        when (r) {
            is CommitResult.Committed -> {
                commitFailing = false
                update(status.copy(lastCommit = Instant.now(), lastCommitFiles = r.files, lastError = null))
            }
            is CommitResult.NoChanges -> commitFailing = false
            is CommitResult.Failed -> {
                update(status.copy(lastError = "Commit failed: ${r.message}"))
                if (!commitFailing || alwaysReport) {
                    commitFailing = true
                    listener.commitFailed(r.message)
                }
            }
        }
        return r
    }

    fun push(alwaysReport: Boolean = false): PushResult {
        val r = git.push()
        when (r) {
            is PushResult.Pushed, is PushResult.UpToDate -> {
                pushFailing = false
                update(status.copy(lastPush = if (r is PushResult.Pushed) Instant.now() else status.lastPush, lastError = null))
            }
            is PushResult.Skipped -> Unit
            is PushResult.Failed -> {
                update(status.copy(lastError = "Push failed: ${r.message}"))
                if (!pushFailing || alwaysReport) {
                    pushFailing = true
                    listener.pushFailed(r.message)
                }
            }
        }
        return r
    }

    fun setFlags(autoCommit: Boolean, push: Boolean) = update(status.copy(autoCommit = autoCommit, push = push))

    private fun update(s: GitSyncStatus) {
        status = s
        listener.statusChanged(s)
    }
}
