package dev.anykeyguru.vaultbridge.editor

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import dev.anykeyguru.vaultbridge.VaultBridgeIcons
import dev.anykeyguru.vaultbridge.model.Note
import dev.anykeyguru.vaultbridge.model.WikiLink
import dev.anykeyguru.vaultbridge.parse.WikiLinkParser
import dev.anykeyguru.vaultbridge.settings.VaultSettings
import javax.swing.Icon

/** Иконка vault у комментариев со `[[ссылками]]`. Маркер ставится на лист — PsiComment. */
class WikiLinkLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {
    override fun getName(): String = "Obsidian wiki-links in comments"

    override fun getIcon(): Icon = VaultBridgeIcons.Vault

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiComment || element.firstChild != null) return null
        val project = element.project
        if (!VaultSettings.getInstance(project).state.showGutterIcons) return null
        if (!element.textContains('[')) return null
        if (links(element).isEmpty()) return null
        WikiLinkSupport.context(project, element.containingFile?.virtualFile) ?: return null
        WikiLinkHighlightRefresher.ensureStarted(project)
        return LineMarkerInfo(
            element,
            element.textRange,
            VaultBridgeIcons.Vault,
            { e -> tooltip(e) },
            NAVIGATE,
            GutterIconRenderer.Alignment.LEFT,
        ) { "Obsidian notes" }
    }

    private class Target(val link: WikiLink, val note: Note)

    companion object {
        private fun links(element: PsiElement): List<WikiLink> =
            WikiLinkParser.parse(element.text).filter { it.target.isNotEmpty() && !WikiLinkText.isAttachment(it.target) }

        /** Резолвим по текущему индексу в момент наведения/клика — снимок мог обновиться. */
        private fun resolved(element: PsiElement): Pair<VaultContext, List<Pair<WikiLink, Note?>>>? {
            val ctx = WikiLinkSupport.context(element.project, element.containingFile?.virtualFile) ?: return null
            return ctx to links(element).map { it to ctx.resolve(it) }
        }

        private fun tooltip(element: PsiElement): String? {
            val (_, items) = resolved(element) ?: return null
            val rows = items.distinctBy { WikiLinkSupport.label(it.first) }.joinToString("<br>") { (link, note) ->
                val label = StringUtil.escapeXmlEntities(WikiLinkSupport.label(link))
                if (note == null) "$label — <i>not found</i>" else "$label — ${StringUtil.escapeXmlEntities(note.path)}"
            }
            return "<html><b>Obsidian notes</b><br>$rows</html>"
        }

        private val NAVIGATE = GutterIconNavigationHandler<PsiElement> { e, element ->
            val (ctx, items) = resolved(element) ?: return@GutterIconNavigationHandler
            val targets = items.mapNotNull { (l, n) -> n?.let { Target(l, it) } }
                .distinctBy { it.note.path + "#" + it.link.heading.orEmpty() }
            val project = element.project
            when (targets.size) {
                0 -> Unit
                1 -> WikiLinkSupport.openNote(project, ctx.root, targets[0].note, targets[0].link.heading)
                else -> JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                    .setTitle("Open Note")
                    .setRenderer(SimpleListCellRenderer.create { label, t, _ ->
                        label.text = t.note.name + (t.link.heading?.let { "#$it" } ?: "") +
                            if (t.note.folder.isNotEmpty()) "  (${t.note.folder})" else ""
                        label.icon = VaultBridgeIcons.Vault
                    })
                    .setItemChosenCallback { WikiLinkSupport.openNote(project, ctx.root, it.note, it.link.heading) }
                    .createPopup()
                    .show(RelativePoint(e))
            }
        }
    }
}
