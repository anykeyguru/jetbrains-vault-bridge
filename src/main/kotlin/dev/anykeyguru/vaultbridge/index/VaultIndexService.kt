package dev.anykeyguru.vaultbridge.index

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.anykeyguru.vaultbridge.model.Note
import dev.anykeyguru.vaultbridge.parse.NoteParser
import dev.anykeyguru.vaultbridge.settings.VaultSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

@Service(Service.Level.PROJECT)
class VaultIndexService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val log = logger<VaultIndexService>()
    private val mutex = Mutex()
    private val notes = HashMap<String, Note>() // под mutex
    private var watch: LocalFileSystem.WatchRequest? = null

    @Volatile
    var state: VaultState = VaultState(null, VaultIndex.EMPTY, indexing = false)
        private set

    val index: VaultIndex get() = state.index

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onVfsEvents(events)
        })
    }

    /** Корень vault из настроек проекта или null, если не задан / не существует. */
    fun configuredRoot(): Path? =
        VaultSettings.getInstance(project).state.vaultPath
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it.trim()).toAbsolutePath().normalize() }

    fun reindex(): Job = cs.launch(Dispatchers.IO) {
        withBackgroundProgress(project, "Indexing Obsidian vault") {
            mutex.withLock { fullScan() }
        }
    }

    private fun fullScan() {
        val root = configuredRoot()
        notes.clear()
        if (root == null) {
            updateWatch(null)
            publish(VaultState(null, VaultIndex.EMPTY, indexing = false))
            return
        }
        if (!root.isDirectory()) {
            updateWatch(null)
            publish(VaultState(root.toString(), VaultIndex.EMPTY, indexing = false, error = "Folder not found"))
            return
        }
        publish(state.copy(root = root.toString(), indexing = true, error = null))
        val started = System.nanoTime()
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.name.endsWith(".md") && isVisible(root, it) }
                .forEach { file -> parseFile(root, file)?.let { notes[it.path] = it } }
        }
        val index = VaultIndex(notes.values)
        log.info("Indexed ${index.size} notes in ${root.name} in ${(System.nanoTime() - started) / 1_000_000} ms")
        publish(VaultState(root.toString(), index, indexing = false))
        updateWatch(root)
    }

    private fun onVfsEvents(events: List<VFileEvent>) {
        val root = configuredRoot() ?: return
        val rootStr = root.toString()
        var needFullScan = false
        val changed = LinkedHashSet<Path>()
        for (e in events) {
            val paths = buildList {
                add(e.path)
                when (e) {
                    is VFileMoveEvent -> add(e.oldPath)
                    is VFilePropertyChangeEvent -> if (e.isRename) add(e.oldPath)
                }
            }
            if (paths.none { it == rootStr || it.startsWith("$rootStr/") }) continue
            val isDir = when (e) {
                is VFileCreateEvent -> e.isDirectory
                is VFileDeleteEvent, is VFileMoveEvent, is VFilePropertyChangeEvent -> e.file?.isDirectory == true
                else -> false
            }
            if (isDir) {
                needFullScan = true
                break
            }
            paths.filter { it.endsWith(".md") }.map { Paths.get(it) }.filter { isVisible(root, it) }.forEach(changed::add)
        }
        if (needFullScan) {
            reindex()
            return
        }
        if (changed.isEmpty()) return
        cs.launch(Dispatchers.IO) {
            mutex.withLock {
                if (configuredRoot() != root) return@withLock
                for (file in changed) {
                    val rel = relPath(root, file)
                    val note = if (file.isRegularFile()) parseFile(root, file) else null
                    if (note != null) notes[rel] = note else notes.remove(rel)
                }
                publish(VaultState(rootStr, VaultIndex(notes.values), indexing = false))
            }
        }
    }

    private fun parseFile(root: Path, file: Path): Note? = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val text = decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString()
        NoteParser.parse(relPath(root, file), text)
    } catch (e: Exception) {
        log.warn("Cannot read $file", e)
        null
    }

    /** IDE получает события только по файлам, которые VFS уже видел, — поэтому грузим дерево и ставим watch. */
    private fun updateWatch(root: Path?) {
        val lfs = LocalFileSystem.getInstance()
        watch?.let { lfs.removeWatchedRoot(it) }
        watch = null
        if (root == null) return
        watch = lfs.addRootToWatch(root.toString(), true)
        val vRoot: VirtualFile = lfs.refreshAndFindFileByNioFile(root) ?: return
        VfsUtil.markDirtyAndRefresh(true, true, true, vRoot)
    }

    private fun publish(newState: VaultState) {
        state = newState
        if (!project.isDisposed) project.messageBus.syncPublisher(VaultIndexListener.TOPIC).indexChanged(newState)
    }

    override fun dispose() {
        watch?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
    }

    companion object {
        fun getInstance(project: Project): VaultIndexService = project.service()

        internal fun relPath(root: Path, file: Path): String = root.relativize(file).joinToString("/")

        /** Пропускаем скрытые папки и файлы: `.obsidian`, `.trash`, `.git`. */
        internal fun isVisible(root: Path, file: Path): Boolean {
            val rel = root.relativize(file)
            return rel.none { it.toString().startsWith(".") } && !rel.startsWith("..")
        }
    }
}
