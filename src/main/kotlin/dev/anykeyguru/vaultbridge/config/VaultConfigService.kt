package dev.anykeyguru.vaultbridge.config

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.anykeyguru.vaultbridge.VaultBridgeIcons
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `.vaultbridge.json` в корне проекта: читает, кэширует, следит за изменениями через VFS.
 * Нет файла — всё как раньше (путь из настроек, никакого git).
 */
@Service(Service.Level.PROJECT)
class VaultConfigService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    @Volatile
    private var cached: ConfigLoad? = null
    private var reportedError: String? = null
    private val reportedMissing = HashSet<String>()

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val path = configFile()?.let { FileUtil.toSystemIndependentName(it.toString()) } ?: return
                if (events.any { touches(it, path) }) cs.launch(Dispatchers.IO) { onConfigChanged() }
            }
        })
    }

    fun configFile(): Path? = project.basePath?.let { Paths.get(it, ConfigParser.FILE_NAME) }

    fun load(): ConfigLoad = cached ?: reload()

    /** Путь к vault из файла, если файл есть, валиден и задаёт `vault`. */
    fun vaultRoot(): Path? = (load() as? ConfigLoad.Loaded)?.vaultRoot

    fun gitConfig(): GitSyncConfig? = (load() as? ConfigLoad.Loaded)?.config?.git

    @Synchronized
    private fun reload(): ConfigLoad {
        val base = project.basePath
        val result = if (base == null) ConfigLoad.Missing else ConfigParser.load(Paths.get(base))
        cached = result
        return result
    }

    /** Старт проекта: показать ошибки конфигурации и запустить git-синхронизацию. */
    fun startup() {
        val now = reload()
        report(now)
        VaultGitSync.getInstance(project).restart()
    }

    private fun onConfigChanged() {
        val before = cached
        val now = reload()
        if (now == before) return
        report(now)
        val oldRoot = (before as? ConfigLoad.Loaded)?.vaultRoot
        val newRoot = (now as? ConfigLoad.Loaded)?.vaultRoot
        if (oldRoot != newRoot || before is ConfigLoad.Loaded != now is ConfigLoad.Loaded) {
            VaultIndexService.getInstance(project).reindex()
        }
        VaultGitSync.getInstance(project).restart()
    }

    private fun report(load: ConfigLoad) {
        when (load) {
            is ConfigLoad.Invalid -> {
                if (reportedError == load.message) return
                reportedError = load.message
                notify(
                    project,
                    "Invalid .vaultbridge.json",
                    "${escape(load.message)}<br>Using the vault from Settings until the file is fixed.",
                    NotificationType.ERROR,
                    openConfigAction(),
                )
            }
            is ConfigLoad.Loaded -> {
                reportedError = null
                load.vaultRoot?.let { checkMissingVault(it, load.config.git?.remote) }
            }
            ConfigLoad.Missing -> reportedError = null
        }
    }

    /** Папки vault нет: с `remote` предлагаем клонировать, без — открыть конфиг. Один раз за сессию на путь. */
    private fun checkMissingVault(root: Path, remote: String?) {
        if (Files.isDirectory(root)) return
        synchronized(reportedMissing) { if (!reportedMissing.add(root.toString())) return }
        val action = if (remote != null) {
            NotificationAction.createSimpleExpiring("Clone vault") {
                VaultGitSync.getInstance(project).cloneVault(remote, root)
            }
        } else {
            openConfigAction()
        }
        notify(
            project,
            "Vault folder not found",
            escape(root.toString()) + if (remote != null) "<br>It can be cloned from ${escape(remote)}" else "",
            NotificationType.WARNING,
            action,
            if (remote != null) openConfigAction() else null,
        )
    }

    fun openConfigAction(): NotificationAction =
        NotificationAction.createSimple("Open .vaultbridge.json") { openConfigFile() }

    /** Открывает файл в редакторе (после закрытия модальных диалогов, например Settings). */
    fun openConfigFile() {
        val file = configFile() ?: return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(vf, true)
        }, ModalityState.nonModal())
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): VaultConfigService = project.service()

        private fun touches(e: VFileEvent, path: String): Boolean {
            if (e.path == path) return true
            return when (e) {
                is VFileMoveEvent -> e.oldPath == path
                is VFilePropertyChangeEvent -> e.isRename && e.oldPath == path
                else -> false
            }
        }

        internal fun escape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

        internal fun notify(
            project: Project,
            title: String,
            content: String,
            type: NotificationType,
            vararg actions: NotificationAction?,
        ) {
            if (project.isDisposed) return
            val n = NotificationGroupManager.getInstance().getNotificationGroup("Vault Bridge")
                .createNotification(title, content, type)
                .setIcon(VaultBridgeIcons.Vault)
            actions.filterNotNull().forEach { n.addAction(it) }
            n.notify(project)
        }
    }
}

class VaultConfigStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        VaultConfigService.getInstance(project).startup()
    }
}
