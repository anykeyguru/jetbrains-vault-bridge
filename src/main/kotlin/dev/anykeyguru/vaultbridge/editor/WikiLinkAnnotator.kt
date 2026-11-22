package dev.anykeyguru.vaultbridge.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.anykeyguru.vaultbridge.model.WikiLink
import dev.anykeyguru.vaultbridge.parse.FrontmatterParser
import dev.anykeyguru.vaultbridge.parse.MarkdownMask
import dev.anykeyguru.vaultbridge.parse.WikiLinkParser
import java.nio.file.Path

/**
 * Слабое предупреждение на `[[ссылках]]`, которые не резолвятся: в комментариях кода
 * и в markdown-заметках самого vault (вне блоков кода).
 */
class WikiLinkAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = holder.currentAnnotationSession.file
        val project = element.project
        val ctx = WikiLinkSupport.context(project, file.virtualFile) ?: return
        WikiLinkHighlightRefresher.ensureStarted(project)

        if (WikiLinkSupport.isMarkdownInVault(file, ctx.root)) {
            annotateMarkdown(element, file, ctx, holder)
        } else if (element is PsiComment && element.firstChild == null && element.textContains('[')) {
            val base = element.textRange.startOffset
            for (link in WikiLinkParser.parse(element.text)) report(link, base, ctx, holder)
        }
    }

    /**
     * В markdown ссылка может быть разбита на несколько PSI-токенов (или лежать в одном PsiPlainText без плагина),
     * поэтому аннотируем самый глубокий элемент, целиком содержащий ссылку: так диапазон не выходит за элемент
     * и каждая ссылка помечается ровно один раз.
     */
    private fun annotateMarkdown(element: PsiElement, file: PsiFile, ctx: VaultContext, holder: AnnotationHolder) {
        if (element is PsiFile) return
        val links = markdownLinks(file)
        if (links.isEmpty()) return
        val range = element.textRange
        var i = firstAtOrAfter(links, range.startOffset)
        var child = element.firstChild
        while (i < links.size && links[i].start < range.endOffset) {
            val link = links[i++]
            if (link.end > range.endOffset) continue
            while (child != null && child.textRange.endOffset <= link.start) child = child.nextSibling
            if (child != null && child.textRange.let { it.startOffset <= link.start && it.endOffset >= link.end }) continue
            report(link, 0, ctx, holder)
        }
    }

    private fun report(link: WikiLink, base: Int, ctx: VaultContext, holder: AnnotationHolder) {
        if (link.target.isEmpty() || WikiLinkText.isAttachment(link.target) || ctx.resolve(link) != null) return
        val builder = holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Note not found in vault: ${link.target}")
            .range(TextRange(base + link.start, base + link.end))
        if (WikiLinkText.isValidNoteName(link.target)) builder.withFix(CreateNoteFix(ctx.root, link.target.trim()))
        builder.create()
    }

    private fun markdownLinks(file: PsiFile): List<WikiLink> = CachedValuesManager.getCachedValue(file) {
        val text = file.text
        val bodyStart = FrontmatterParser.parse(text).bodyStart
        CachedValueProvider.Result.create(
            WikiLinkParser.parse(MarkdownMask.maskCode(text)).filter { it.start >= bodyStart },
            file,
        )
    }

    private fun firstAtOrAfter(links: List<WikiLink>, offset: Int): Int {
        var lo = 0
        var hi = links.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (links[mid].start < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** Quick fix: создать `<vault>/<target>.md` (с подпапками) и открыть. */
private class CreateNoteFix(private val root: Path, private val target: String) : IntentionAction, DumbAware {
    override fun getText(): String = "Create note '$target'"
    override fun getFamilyName(): String = "Create Obsidian note"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val rel = target.removeSuffix(".md") + ".md"
        val folder = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val lfs = LocalFileSystem.getInstance()
        val rootDir = lfs.findFileByNioFile(root) ?: lfs.refreshAndFindFileByNioFile(root) ?: return
        val created = try {
            WriteAction.compute<VirtualFile?, Exception> {
                val dir = if (folder.isEmpty()) rootDir else VfsUtil.createDirectoryIfMissing(rootDir, folder)
                dir?.findChild(name) ?: dir?.createChildData(this, name)
            }
        } catch (e: Exception) {
            logger<CreateNoteFix>().warn("Cannot create note $rel", e)
            Messages.showErrorDialog(project, "Cannot create $rel: ${e.message}", "Vault Bridge")
            return
        }
        if (created != null) FileEditorManager.getInstance(project).openFile(created, true)
    }
}
