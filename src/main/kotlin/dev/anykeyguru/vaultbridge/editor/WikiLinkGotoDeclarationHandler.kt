package dev.anykeyguru.vaultbridge.editor

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import dev.anykeyguru.vaultbridge.VaultBridgeIcons
import javax.swing.Icon

/** Cmd+Click / Cmd+B по `[[ссылке]]` в комментарии, строке, markdown или тексте открывает заметку. */
class WikiLinkGotoDeclarationHandler : GotoDeclarationHandler, DumbAware {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        if (sourceElement == null) return null
        val link = WikiLinkText.linkAt(editor.document.charsSequence, offset) ?: return null
        if (!WikiLinkSupport.isTextContext(sourceElement)) return null
        val project = sourceElement.project
        val ctx = WikiLinkSupport.context(project, sourceElement.containingFile?.virtualFile) ?: return null
        val note = ctx.resolve(link) ?: return null
        val vf = WikiLinkSupport.noteFile(ctx.root, note) ?: return null
        val psi = PsiManager.getInstance(project).findFile(vf) ?: return null
        val headingOffset = WikiLinkSupport.headingOffset(vf, link.heading) ?: return arrayOf(psi)
        return arrayOf(NoteHeadingTarget(psi, headingOffset, "${note.name}#${link.heading}"))
    }
}

/** Цель «заголовок внутри заметки»: навигация ставит каретку на строку заголовка. */
private class NoteHeadingTarget(
    private val file: PsiFile,
    private val offset: Int,
    private val label: String,
) : FakePsiElement() {
    override fun getParent(): PsiElement = file
    override fun getContainingFile(): PsiFile = file
    override fun getTextOffset(): Int = offset
    override fun getTextRange(): TextRange = TextRange(offset, offset)
    override fun getName(): String = label
    override fun getPresentableText(): String = label
    override fun getLocationString(): String? = file.virtualFile?.parent?.name
    override fun getIcon(open: Boolean): Icon = VaultBridgeIcons.Vault
    override fun isValid(): Boolean = file.isValid
    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
    override fun getNavigationElement(): PsiElement = this
    override fun navigate(requestFocus: Boolean) {
        val vf = file.virtualFile ?: return
        OpenFileDescriptor(file.project, vf, offset).navigate(requestFocus)
    }
}
