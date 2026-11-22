package dev.anykeyguru.vaultbridge.editor

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import dev.anykeyguru.vaultbridge.VaultBridgeIcons
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.model.Note

/**
 * Имена заметок и алиасы после `[[` — в комментариях, строках, markdown и тексте.
 * Префикс ищем по тексту документа, поэтому markdown-плагин не нужен.
 */
class WikiLinkCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset
        WikiLinkText.headingQuery(text, offset)?.let { query ->
            addHeadings(parameters, result, query)
            return
        }
        val start = WikiLinkText.completionPrefixStart(text, offset) ?: return
        if (!WikiLinkSupport.isTextContext(parameters.position)) return
        val project = parameters.position.project
        val ctx = WikiLinkSupport.context(project, parameters.originalFile.virtualFile) ?: return
        val notes = ctx.index.notes.values
        if (notes.isEmpty()) return

        val rs = result.withPrefixMatcher(CamelHumpMatcher(text.subSequence(start, offset).toString(), false))
        val nameCount = notes.groupingBy { it.name.lowercase() }.eachCount()
        for (note in notes) {
            // Как Obsidian: имя, если оно уникально, иначе путь без .md.
            val target = if (nameCount[note.name.lowercase()] == 1) note.name else note.path.removeSuffix(".md")
            rs.addElement(noteElement(note, target))
            for (alias in note.aliases) rs.addElement(aliasElement(note, target, alias))
        }
        // После `[[` прочие варианты (слова, ключевые слова) только мешают.
        rs.stopHere()
    }

    /** `[[note#` — все заголовки, `[[note##` — только второго уровня и т.д. Вставляем с одной `#`, как Obsidian. */
    private fun addHeadings(parameters: CompletionParameters, result: CompletionResultSet, query: HeadingQuery) {
        if (!WikiLinkSupport.isTextContext(parameters.position)) return
        val project = parameters.position.project
        val ctx = WikiLinkSupport.context(project, parameters.originalFile.virtualFile) ?: return
        val noteText = if (query.target.isEmpty()) {
            // [[#… — заголовки текущего документа
            parameters.originalFile.viewProvider.document?.text ?: return
        } else {
            val note = ctx.index.resolve(query.target, ctx.fromPath) ?: return
            val vf = WikiLinkSupport.noteFile(ctx.root, note) ?: return
            FileDocumentManager.getInstance().getDocument(vf)?.text ?: return
        }
        val headings = WikiLinkText.headings(noteText).filter { query.level == null || it.level == query.level }
        val text = parameters.editor.document.charsSequence
        val rs = result.withPrefixMatcher(CamelHumpMatcher(text.subSequence(query.prefixStart, parameters.offset).toString(), false))
        headings.forEachIndexed { i, h ->
            val linkText = WikiLinkText.headingForLink(h.text)
            if (linkText.isEmpty()) return@forEachIndexed
            val element = LookupElementBuilder.create(HeadingItem(i, linkText), linkText)
                .withPresentableText("  ".repeat(h.level - 1) + h.text)
                .withTypeText("#".repeat(h.level), true)
                .withIcon(VaultBridgeIcons.Vault)
                .withInsertHandler { ic, _ ->
                    val insert = "#$linkText"
                    ic.document.replaceString(query.hashStart, ic.tailOffset, insert)
                    closeLink(ic, query.hashStart + insert.length)
                }
            // порядок как в документе, а не по алфавиту
            rs.addElement(PrioritizedLookupElement.withPriority(element, (headings.size - i).toDouble()))
        }
        rs.stopHere()
    }

    private data class HeadingItem(val index: Int, val text: String)

    private fun noteElement(note: Note, target: String): LookupElement =
        LookupElementBuilder.create(note, target)
            .withLookupStrings(setOf(target, note.name))
            .withPresentableText(note.name)
            .withTailText(if (note.title != note.name) "  ${note.title}" else null, true)
            .withTypeText(note.folder.ifEmpty { "/" }, true)
            .withIcon(VaultBridgeIcons.Vault)
            .withInsertHandler { ctx, _ -> closeLink(ctx, ctx.tailOffset) }

    private fun aliasElement(note: Note, target: String, alias: String): LookupElement =
        LookupElementBuilder.create(AliasItem(note.path, alias), alias)
            .withTailText("  alias", true)
            .withTypeText("→ ${note.name}", true)
            .withIcon(VaultBridgeIcons.Vault)
            .withInsertHandler { ctx, _ ->
                val text = "$target|$alias"
                ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, text)
                closeLink(ctx, ctx.startOffset + text.length)
            }

    private data class AliasItem(val path: String, val alias: String)

    private fun closeLink(ctx: InsertionContext, tail: Int) {
        val have = WikiLinkText.closingBracketsAt(ctx.document.charsSequence, tail)
        if (have < 2) ctx.document.insertString(tail, "]]".substring(have))
        ctx.editor.caretModel.moveToOffset(tail + 2)
    }
}

/** Авто-попап сразу после второй `[`. Контекст проверяем уже по закоммиченному PSI. */
class WikiLinkTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        val trigger = when (charTyped) {
            '[' -> offset > 0 && text[offset - 1] == '['
            '#' -> WikiLinkText.openLinkStart(text, offset) != null // [[note# — заголовки
            else -> false
        }
        if (!trigger) return Result.CONTINUE
        if (VaultIndexService.getInstance(project).configuredRoot() == null) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor, CompletionType.BASIC) { psiFile ->
            val caret = editor.caretModel.offset
            val element = psiFile.findElementAt(maxOf(0, caret - 1))
            element != null && WikiLinkSupport.isTextContext(element)
        }
        return Result.STOP
    }
}

/** Не даём глушить авто-попап в комментариях/строках, когда каретка стоит после `[[`. */
class WikiLinkCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        val text = editor.document.charsSequence
        if (offset > text.length) return ThreeState.UNSURE
        if (WikiLinkText.completionPrefixStart(text, offset) == null && WikiLinkText.headingQuery(text, offset) == null) {
            return ThreeState.UNSURE
        }
        return if (WikiLinkSupport.isTextContext(contextElement)) ThreeState.NO else ThreeState.UNSURE
    }
}
