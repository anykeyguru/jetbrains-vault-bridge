package dev.anykeyguru.vaultbridge.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import dev.anykeyguru.vaultbridge.index.VaultIndex
import dev.anykeyguru.vaultbridge.index.VaultIndexListener
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.model.Note
import dev.anykeyguru.vaultbridge.model.WikiLink
import java.nio.file.Path
import java.nio.file.Paths

/** Снимок vault для одного файла: корень, индекс и путь файла внутри vault (для резолва одноимённых). */
internal class VaultContext(val root: Path, val index: VaultIndex, val fromPath: String?) {
    fun resolve(link: WikiLink): Note? =
        if (link.target.isEmpty()) fromPath?.let { index.notes[it] } // [[#Heading]] — в этой же заметке
        else index.resolve(link.target, fromPath)
}

internal object WikiLinkSupport {
    private val DOC_EXTENSIONS = setOf("md", "markdown", "txt")

    /** null, если vault не настроен. Индекс — in-memory снимок, читать можно из любого потока. */
    fun context(project: Project, file: VirtualFile?): VaultContext? {
        val svc = VaultIndexService.getInstance(project)
        val root = svc.configuredRoot() ?: return null
        return VaultContext(root, svc.index, vaultRelativePath(root, file))
    }

    fun vaultRelativePath(root: Path, file: VirtualFile?): String? {
        if (file == null || !file.isInLocalFileSystem) return null
        val p = runCatching { Paths.get(file.path).normalize() }.getOrNull() ?: return null
        return if (p.startsWith(root) && p != root) root.relativize(p).joinToString("/") else null
    }

    /** Markdown или обычный текст — там ссылки допустимы в любом месте, а не только в комментариях. */
    fun isDocumentFile(file: PsiFile): Boolean {
        val ext = file.virtualFile?.extension?.lowercase()
        return ext in DOC_EXTENSIONS || file.language.id == "Markdown" || file.language.id == "TEXT"
    }

    fun isMarkdownInVault(file: PsiFile, root: Path): Boolean {
        val vf = file.virtualFile ?: return false
        return vf.extension.equals("md", ignoreCase = true) && vaultRelativePath(root, vf) != null
    }

    fun isComment(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null

    fun isStringLiteral(element: PsiElement): Boolean {
        val type = element.node?.elementType ?: return false
        val def = LanguageParserDefinitions.INSTANCE.forLanguage(element.language)
        if (def != null && def.stringLiteralElements.contains(type)) return true
        return element.parent is PsiLanguageInjectionHost
    }

    /** Где уместны `[[ссылки]]`: комментарии, строковые литералы, markdown/текст. */
    fun isTextContext(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        return isDocumentFile(file) || isComment(element) || isStringLiteral(element)
    }

    fun noteFile(root: Path, note: Note): VirtualFile? =
        LocalFileSystem.getInstance().findFileByNioFile(root.resolve(note.path))

    /** Смещение заголовка в заметке; документ читается через FileDocumentManager (кэшируется платформой). */
    fun headingOffset(file: VirtualFile, heading: String?): Int? {
        if (heading.isNullOrBlank()) return null
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return WikiLinkText.headingOffset(doc.text, heading)
    }

    /** Открывает заметку (и переходит к заголовку). Вызывать на EDT. */
    fun openNote(project: Project, root: Path, note: Note, heading: String?) {
        val vf = noteFile(root, note)
        if (vf != null) {
            OpenFileDescriptor(project, vf, headingOffset(vf, heading) ?: 0).navigate(true)
            return
        }
        // VFS ещё не видел файл — обновляем вне EDT, открываем потом.
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(note.path)) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                OpenFileDescriptor(project, file, headingOffset(file, heading) ?: 0).navigate(true)
            }, project.disposed)
        }
    }

    fun label(link: WikiLink): String = link.target + (link.heading?.let { "#$it" } ?: "")
}

/** Перезапускает подсветку открытых файлов при каждом изменении индекса: битые ссылки «оживают» сразу. */
@Service(Service.Level.PROJECT)
internal class WikiLinkHighlightRefresher(private val project: Project) : Disposable {
    init {
        project.messageBus.connect(this).subscribe(VaultIndexListener.TOPIC, VaultIndexListener { state ->
            if (state.indexing) return@VaultIndexListener
            ApplicationManager.getApplication().invokeLater({
                DaemonCodeAnalyzer.getInstance(project).restart()
            }, project.disposed)
        })
    }

    override fun dispose() = Unit

    companion object {
        fun ensureStarted(project: Project) {
            project.service<WikiLinkHighlightRefresher>()
        }
    }
}
