package dev.anykeyguru.vaultbridge.config

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.anykeyguru.vaultbridge.config.VaultConfigService.Companion.escape
import dev.anykeyguru.vaultbridge.config.VaultConfigService.Companion.notify
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Автокоммит и пуш vault по расписанию из `.vaultbridge.json`. Все git-операции — на Dispatchers.IO
 * и под одним Mutex. Перезапускается при изменении конфигурации.
 */
@Service(Service.Level.PROJECT)
class VaultGitSync(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val log = logger<VaultGitSync>()
    private val mutex = Mutex()
    private var job: Job? = null
    private var steps: GitSyncSteps? = null // под mutex
    private var stepsRoot: Path? = null
    private var notRepoNotified = false

    /** Последний коммит/пуш/ошибка — для диагностики и статус-бара. */
    @Volatile
    var status: GitSyncStatus = GitSyncStatus()
        private set

    private val listener = object : GitSyncSteps.Listener {
        override fun commitFailed(message: String) =
            notify(project, "Vault auto-commit failed", escape(message), NotificationType.WARNING)

        override fun pushFailed(message: String) = notify(
            project,
            "Vault push failed",
            escape(message) + "<br>Will retry at the next interval.",
            NotificationType.WARNING,
        )

        override fun statusChanged(status: GitSyncStatus) {
            this@VaultGitSync.status = status
        }
    }

    /** Перечитать конфигурацию и перезапустить расписание. */
    @Synchronized
    fun restart() {
        job?.cancel()
        val cfg = VaultConfigService.getInstance(project).gitConfig()
        val root = VaultIndexService.getInstance(project).configuredRoot()
        if (cfg == null || root == null || (!cfg.autoCommit && !cfg.push)) {
            status = status.copy(autoCommit = false, push = false)
            job = null
            return
        }
        job = cs.launch(Dispatchers.IO) {
            val s = mutex.withLock { stepsFor(root) }
            if (s == null) {
                status = status.copy(repoDetected = false, autoCommit = false, push = false)
                if (Files.isDirectory(root)) suggestInit(root)
                return@launch
            }
            mutex.withLock { s.setFlags(cfg.autoCommit, cfg.push) }
            if (cfg.autoCommit) launch { loop(cfg.commitInterval) { s.commit() } }
            if (cfg.push) launch { loop(cfg.pushInterval) { s.push() } }
        }
    }

    private suspend fun loop(interval: Duration, step: () -> Unit) {
        while (true) {
            delay(interval.toMillis())
            mutex.withLock {
                try {
                    step()
                } catch (e: Exception) {
                    log.warn("Vault git sync step failed", e)
                }
            }
        }
    }

    /** Под mutex. null — vault не git-репозиторий. */
    private fun stepsFor(root: Path): GitSyncSteps? {
        if (stepsRoot == root && steps != null) return steps
        val git = VaultGit(root)
        if (!git.isWorkTree()) {
            steps = null
            stepsRoot = null
            return null
        }
        return GitSyncSteps(git, listener).also {
            steps = it
            stepsRoot = root
            status = it.status
        }
    }

    /** Есть ли у vault git-репозиторий (для enable действия «Commit Vault Now»). Без запуска git. */
    fun repoLikely(): Boolean {
        if (status.repoDetected) return true
        var dir: Path? = VaultIndexService.getInstance(project).configuredRoot() ?: return false
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) return true
            dir = dir.parent
        }
        return false
    }

    /** «Commit Vault Now»: коммит сразу, плюс пуш, если он включён в конфиге. */
    fun commitNow() {
        val root = VaultIndexService.getInstance(project).configuredRoot() ?: return
        val pushEnabled = VaultConfigService.getInstance(project).gitConfig()?.push == true
        cs.launch(Dispatchers.IO) {
            withBackgroundProgress(project, "Committing vault") {
                mutex.withLock {
                    val s = stepsFor(root)
                    if (s == null) {
                        notify(project, "Vault is not a git repository", escape(root.toString()), NotificationType.INFORMATION, initAction(root))
                        return@withLock
                    }
                    val commit = s.commit(alwaysReport = true)
                    val push = if (pushEnabled) s.push(alwaysReport = true) else null
                    val text = when (commit) {
                        is CommitResult.Committed -> commit.message
                        CommitResult.NoChanges -> "Nothing to commit"
                        is CommitResult.Failed -> return@withLock // уже показано
                    } + when (push) {
                        PushResult.Pushed -> ", pushed"
                        PushResult.UpToDate -> ", remote is up to date"
                        is PushResult.Skipped -> ", not pushed: ${push.reason}"
                        else -> ""
                    }
                    if (push !is PushResult.Failed) notify(project, "Vault", escape(text), NotificationType.INFORMATION)
                }
            }
        }
    }

    private fun suggestInit(root: Path) {
        if (notRepoNotified) return
        notRepoNotified = true
        notify(
            project,
            "Vault is not a git repository",
            "Auto-commit from <code>.vaultbridge.json</code> is off for ${escape(root.toString())}.",
            NotificationType.INFORMATION,
            initAction(root),
        )
    }

    private fun initAction(root: Path) = NotificationAction.createSimpleExpiring("Initialize git") {
        cs.launch(Dispatchers.IO) {
            withBackgroundProgress(project, "Initializing git in vault") {
                val error = mutex.withLock { VaultGit(root).initRepository() }
                if (error != null) {
                    notify(project, "Cannot initialize git in vault", escape(error), NotificationType.WARNING)
                } else {
                    notify(project, "Vault is now a git repository", "Initial commit created.", NotificationType.INFORMATION)
                }
                mutex.withLock { steps = null; stepsRoot = null }
                restart()
            }
        }
    }

    /** `git clone <remote> <path>` в фоне, затем reindex и перезапуск синхронизации. */
    fun cloneVault(remote: String, target: Path) {
        cs.launch(Dispatchers.IO) {
            withBackgroundProgress(project, "Cloning vault from $remote") {
                val r = mutex.withLock {
                    try {
                        VaultGit.clone(SystemGit, remote, target)
                    } catch (e: Exception) {
                        GitResult(-1, "", e.message ?: e.toString())
                    }
                }
                if (r.ok) {
                    notify(project, "Vault cloned", escape(target.toString()), NotificationType.INFORMATION)
                    VaultIndexService.getInstance(project).reindex()
                    restart()
                } else {
                    notify(project, "Cannot clone vault", escape(r.errorTail()), NotificationType.WARNING)
                }
            }
        }
    }

    /**
     * Закрытие проекта: последний коммит, если autoCommit включён и есть изменения. Best effort —
     * в пуловом потоке с коротким таймаутом, EDT не блокируем; при выходе из IDE может не успеть.
     */
    override fun dispose() {
        if (steps == null || !status.autoCommit) return
        val root = stepsRoot ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                withTimeoutOrNull(5_000) { mutex.lock() } ?: return@runBlocking
                try {
                    VaultGit(root, shortTimeout = Duration.ofSeconds(5), longTimeout = Duration.ofSeconds(10))
                        .commitIfChanged()
                } catch (e: Exception) {
                    log.info("Vault commit on close failed", e)
                } finally {
                    mutex.unlock()
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): VaultGitSync = project.service()
    }
}
