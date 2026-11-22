package dev.anykeyguru.vaultbridge.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.ClickListener
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import dev.anykeyguru.vaultbridge.index.VaultIndexListener
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.index.VaultState
import dev.anykeyguru.vaultbridge.settings.VaultConfigurable
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.JComponent

class VaultStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Vault Bridge"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = VaultStatusBarWidget(project)

    companion object {
        const val ID = "VaultBridge.Status"
    }
}

/** Что показать в статус-баре; чистая функция от состояния индекса. */
internal data class VaultStatusView(val text: String, val tooltip: String, val error: Boolean) {
    companion object {
        const val MISSING_HINT = "check Settings > Tools > Vault Bridge or .vaultbridge.json"

        fun of(s: VaultState): VaultStatusView = when {
            s.root == null -> VaultStatusView("Vault: not set", "Click to choose an Obsidian vault", false)
            s.error != null -> VaultStatusView("Vault: missing", "${s.root}\n${s.error} — $MISSING_HINT", true)
            else -> VaultStatusView(
                if (s.indexing) "Vault: ${s.name} · indexing…" else "Vault: ${s.name} · ${s.index.size} notes",
                "${s.root}\n${s.index.size} notes, ${s.index.tags.size} tags, ${s.index.unresolvedLinks} unresolved links",
                false,
            )
        }
    }
}

/** TextPresentation не умеет красить текст, поэтому свой виджет на JBLabel: «Vault: missing» — красным. */
private class VaultStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel().apply {
        border = JBUI.CurrentTheme.StatusBar.Widget.border()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    init {
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (!project.isDisposed) ShowSettingsUtil.getInstance().showSettingsDialog(project, VaultConfigurable::class.java)
                return true
            }
        }.installOn(label, true)
        refresh()
    }

    override fun ID(): String = VaultStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        // Перерисовываемся на каждое изменение индекса; событие приходит из фонового потока.
        project.messageBus.connect(this).subscribe(VaultIndexListener.TOPIC, VaultIndexListener {
            ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
        })
    }

    private fun refresh() {
        val view = VaultStatusView.of(VaultIndexService.getInstance(project).state)
        label.text = view.text
        label.foreground = if (view.error) NamedColorUtil.getErrorForeground() else JBUI.CurrentTheme.StatusBar.Widget.FOREGROUND
        label.toolTipText = "<html>" + view.tooltip.split('\n').joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
    }

    override fun dispose() = Unit
}
