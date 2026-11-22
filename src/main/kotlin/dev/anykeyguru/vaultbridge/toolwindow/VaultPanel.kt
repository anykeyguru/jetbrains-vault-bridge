package dev.anykeyguru.vaultbridge.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.StatusText
import dev.anykeyguru.vaultbridge.index.VaultIndexListener
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.index.VaultState
import dev.anykeyguru.vaultbridge.settings.VaultConfigurable
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Общая основа панелей: подписка на индекс и смену редактора (склеиваем всплески в один refresh на EDT),
 * фоновые задачи с отбрасыванием устаревших результатов, пустые состояния.
 */
abstract class VaultPanel(protected val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val log = logger<VaultPanel>()
    private val pending = AtomicBoolean()
    private var pendingIndexChange = false
    private var generation = 0

    @Volatile
    protected var disposed = false
        private set

    protected val vaultState: VaultState get() = VaultIndexService.getInstance(project).state

    /** Вызывать в конце init наследника. */
    protected fun subscribe() {
        val bus = project.messageBus.connect(this)
        bus.subscribe(VaultIndexListener.TOPIC, VaultIndexListener { scheduleRefresh(indexChanged = true) })
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh(indexChanged = false)
        })
        refresh(indexChanged = true)
    }

    private fun scheduleRefresh(indexChanged: Boolean) {
        synchronized(pending) { pendingIndexChange = pendingIndexChange || indexChanged }
        if (!pending.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            pending.set(false)
            val changed = synchronized(pending) { pendingIndexChange.also { pendingIndexChange = false } }
            refresh(changed)
        }, { disposed || project.isDisposed })
    }

    /** EDT. [indexChanged] — пришёл новый снимок индекса, а не только смена вкладки. */
    protected abstract fun refresh(indexChanged: Boolean)

    /** Считает [compute] в пуле, применяет на EDT, если за это время не запустили новую задачу. */
    protected fun <T> background(compute: () -> T, apply: (T) -> Unit) {
        val gen = ++generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                compute()
            } catch (e: Exception) {
                log.warn(e)
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater(
                { if (gen == generation) apply(result) },
                { disposed || project.isDisposed },
            )
        }
    }

    /** Отменяет ожидающие результаты фоновых задач. */
    protected fun cancelBackground() {
        generation++
    }

    /** Текущая заметка или текст пустого состояния. */
    protected fun currentNoteOrEmpty(status: StatusText, notNote: String): String? {
        val s = vaultState
        if (!fillVaultEmptyText(status, s)) return null
        val path = currentNotePath(project, s)
        if (path == null) status.text = notNote
        return path
    }

    /** false — vault не настроен / недоступен / индексируется, [status] уже заполнен. */
    protected fun fillVaultEmptyText(status: StatusText, s: VaultState): Boolean {
        status.clear()
        when {
            s.root == null -> status.appendText("No Obsidian vault configured")
            s.error != null -> status.appendText("Vault: ${s.error}")
            s.indexing && s.index.size == 0 -> { status.appendText("Indexing vault…"); return false }
            else -> return true
        }
        status.appendSecondaryText("Configure vault…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, VaultConfigurable::class.java)
        }
        return false
    }

    override fun dispose() {
        disposed = true
    }

    companion object {
        /** Относительный путь с `/`, если файл лежит внутри vault. */
        fun relPath(root: String, filePath: String): String? {
            val r = root.replace('\\', '/').trimEnd('/')
            val f = filePath.replace('\\', '/')
            return if (f.startsWith("$r/")) f.substring(r.length + 1) else null
        }

        fun currentNotePath(project: Project, s: VaultState): String? {
            val root = s.root ?: return null
            val file = FileEditorManager.getInstance(project).selectedEditor?.file ?: return null
            return relPath(root, file.path)?.takeIf { it in s.index.notes }
        }

        /** [line] — 0-based строка или -1, тогда используется [offset]. */
        fun openNote(project: Project, path: String, line: Int = -1, offset: Int = 0) {
            val root = VaultIndexService.getInstance(project).state.root ?: return
            val nio = Paths.get(root).resolve(path)
            val lfs = LocalFileSystem.getInstance()
            val file = lfs.findFileByNioFile(nio) ?: lfs.refreshAndFindFileByNioFile(nio) ?: return
            val descriptor = if (line >= 0) OpenFileDescriptor(project, file, line, 0) else OpenFileDescriptor(project, file, offset)
            descriptor.navigate(true)
        }
    }
}

/** Фабрика tool window: одна панель на окно, диспоузится вместе с контентом. */
abstract class VaultToolWindowFactory : ToolWindowFactory, DumbAware {
    abstract fun createPanel(project: Project): VaultPanel

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = createPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
