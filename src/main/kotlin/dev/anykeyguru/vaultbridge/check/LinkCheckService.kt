package dev.anykeyguru.vaultbridge.check

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.util.PsiTreeUtil
import dev.anykeyguru.vaultbridge.editor.WikiLinkSupport
import dev.anykeyguru.vaultbridge.editor.WikiLinkText
import dev.anykeyguru.vaultbridge.index.VaultIndex
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.model.Note
import dev.anykeyguru.vaultbridge.model.WikiLink
import dev.anykeyguru.vaultbridge.parse.MarkdownMask
import dev.anykeyguru.vaultbridge.parse.WikiLinkParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.isDirectory

/**
 * «Check Note Links in Project»: обходит файлы проекта в фоне и проверяет `[[ссылки]]` по индексу vault.
 *
 * Какие ссылки считаем: в `.md`/`.markdown`/`.txt` (и plain text) — все, кроме блоков кода markdown;
 * в остальных файлах — только те, что PSI относит к комментарию или строковому литералу (как и автодополнение).
 * PSI строим только для файлов, где в тексте вообще есть `[[`, по одному файлу в неблокирующем read action.
 */
@Service(Service.Level.PROJECT)
class LinkCheckService(private val project: Project) {
    private val log = logger<LinkCheckService>()
    private val running = AtomicBoolean()

    @Volatile
    var lastResult: LinkCheckResult? = null
        private set

    /** Панель tool window, если её уже создали. EDT. */
    internal var panel: LinkCheckPanel? = null

    val isRunning: Boolean get() = running.get()

    fun run() {
        val svc = VaultIndexService.getInstance(project)
        val root = svc.configuredRoot() ?: return
        val state = svc.state
        if (state.indexing && state.index.size == 0 && root.isDirectory()) {
            notify("The vault is still being indexed — try again in a moment.", NotificationType.INFORMATION)
            return
        }
        if (!running.compareAndSet(false, true)) return
        panel?.setRunning(true)
        object : Task.Backgroundable(project, "Checking note links", true) {
            private var result: LinkCheckResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = scan(root, svc.index, indicator)
            }

            override fun onSuccess() {
                result?.let(::publish)
            }

            override fun onFinished() {
                running.set(false)
                panel?.setRunning(false)
            }
        }.queue()
    }

    private fun publish(r: LinkCheckResult) {
        if (project.isDisposed) return
        lastResult = r
        panel?.show(r)
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (r.vaultMissing || r.broken.isNotEmpty()) {
            tw?.isAvailable = true
            tw?.activate(null)
        } else {
            notify(LinkCheck.okMessage(r), NotificationType.INFORMATION)
        }
    }

    private fun scan(root: Path, index: VaultIndex, indicator: ProgressIndicator): LinkCheckResult {
        val vaultOk = root.isDirectory()
        indicator.text = "Collecting project files…"
        val files = collectFiles(root, indicator)
        indicator.isIndeterminate = false
        val broken = ArrayList<BrokenLink>()
        val noteTexts = HashMap<String, String?>()
        val readNote: (Note) -> String? = { note -> noteTexts.getOrPut(note.path) { readNote(root.resolve(note.path)) } }
        var checked = 0
        for ((i, file) in files.withIndex()) {
            indicator.checkCanceled()
            indicator.fraction = i.toDouble() / files.size
            indicator.text2 = file.presentableUrl
            val (text, links) = linksIn(file) ?: continue
            for (link in links) {
                if (!vaultOk) {
                    if (link.target.isNotBlank() && !WikiLinkText.isAttachment(link.target)) checked++
                    continue
                }
                val verdict = LinkCheck.classify(link, index, readNote)
                if (verdict == LinkVerdict.SKIPPED) continue
                checked++
                val problem = when (verdict) {
                    LinkVerdict.MISSING_NOTE -> LinkProblem.MISSING_NOTE
                    LinkVerdict.MISSING_HEADING -> LinkProblem.MISSING_HEADING
                    else -> continue
                }
                broken += BrokenLink(
                    filePath = file.path,
                    fileLabel = label(file),
                    line = LinkCheck.lineOf(text, link.start),
                    offset = link.start,
                    link = LinkCheck.linkText(link),
                    problem = problem,
                    reason = LinkCheck.reason(verdict, link),
                )
            }
        }
        return LinkCheckResult(checked, files.size, broken, vaultMissing = !vaultOk, vaultPath = root.toString())
    }

    /** Текстовые файлы контента проекта: без исключённых/игнорируемых, бинарных, больших и файлов самого vault. */
    private fun collectFiles(root: Path, indicator: ProgressIndicator): List<VirtualFile> {
        val rootStr = root.toString()
        val inVault = { f: VirtualFile -> f.path == rootStr || f.path.startsWith("$rootStr/") }
        val out = ArrayList<VirtualFile>()
        runReadAction {
            ProjectFileIndex.getInstance(project).iterateContent({ f ->
                indicator.checkCanceled()
                if (!f.isDirectory && !inVault(f) && f.isValid && f.length <= MAX_FILE_SIZE && !f.fileType.isBinary) out += f
                true
            }) { f -> !inVault(f) }
        }
        return out
    }

    /** Текст файла и ссылки, которые стоит проверять, или null, если ссылок нет. */
    private fun linksIn(file: VirtualFile): Pair<CharSequence, List<WikiLink>>? {
        val raw = try {
            LoadTextUtil.loadText(file)
        } catch (e: Exception) {
            log.debug("Cannot read ${file.path}", e)
            return null
        }
        if (!raw.contains("[[")) return null
        if (isDocumentFile(file)) {
            val text = runReadAction { FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence } ?: raw
            val masked = if (file.extension.equals("txt", true)) text else MarkdownMask.maskCode(text.toString())
            return WikiLinkParser.parse(masked).takeIf { it.isNotEmpty() }?.let { text to it }
        }
        return ReadAction.nonBlocking<Pair<CharSequence, List<WikiLink>>?> {
            if (!file.isValid) return@nonBlocking null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@nonBlocking null
            val text = psi.viewProvider.contents
            val all = WikiLinkParser.parse(text)
            val links = if (psi is PsiPlainTextFile) all
            else all.filter { link -> psi.findElementAt(link.start + if (link.embed) 3 else 2)?.let(::isTextContext) == true }
            links.takeIf { it.isNotEmpty() }?.let { text to it }
        }.executeSynchronously()
    }

    private fun label(file: VirtualFile): String {
        val base = project.basePath
        if (base != null && file.path.startsWith("$base/")) return file.path.substring(base.length + 1)
        val contentRoot = runReadAction { ProjectFileIndex.getInstance(project).getContentRootForFile(file) }
        return contentRoot?.let { VfsUtilCore.getRelativePath(file, it.parent ?: it) } ?: file.presentableUrl
    }

    /** Предпочитаем открытый документ (несохранённые правки), иначе читаем с диска. */
    private fun readNote(path: Path): String? {
        LocalFileSystem.getInstance().findFileByNioFile(path)?.let { vf ->
            runReadAction { FileDocumentManager.getInstance().getCachedDocument(vf)?.text }?.let { return it }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Vault Bridge")
            .createNotification("Vault Link Check", text, type)
            .notify(project)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Vault Link Check"
        private const val MAX_FILE_SIZE = 2L * 1024 * 1024
        private val DOC_EXTENSIONS = setOf("md", "markdown", "txt")

        fun getInstance(project: Project): LinkCheckService = project.service()

        private fun isDocumentFile(file: VirtualFile): Boolean =
            file.extension?.lowercase() in DOC_EXTENSIONS || file.fileType == PlainTextFileType.INSTANCE

        /** Комментарий, строковый литерал или его часть (в Kotlin/Python лист строки лежит глубже хоста). */
        private fun isTextContext(element: PsiElement): Boolean =
            PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null ||
                WikiLinkSupport.isStringLiteral(element) ||
                PsiTreeUtil.getParentOfType(element, PsiLanguageInjectionHost::class.java, false, PsiComment::class.java)
                    ?.let { PsiTreeUtil.getDepth(element, it) <= 3 } == true
    }
}
