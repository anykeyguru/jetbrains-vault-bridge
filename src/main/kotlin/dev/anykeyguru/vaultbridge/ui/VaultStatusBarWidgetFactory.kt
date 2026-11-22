package dev.anykeyguru.vaultbridge.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import dev.anykeyguru.vaultbridge.index.VaultIndexListener
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.index.VaultState
import dev.anykeyguru.vaultbridge.settings.VaultConfigurable
import java.awt.Component
import java.awt.event.MouseEvent

class VaultStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Vault Bridge"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = VaultStatusBarWidget(project)

    companion object {
        const val ID = "VaultBridge.Status"
    }
}

private class VaultStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    override fun ID(): String = VaultStatusBarWidgetFactory.ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Перерисовываемся на каждое изменение индекса — в отличие от оригинала, где счётчик застывал.
        project.messageBus.connect(this).subscribe(VaultIndexListener.TOPIC, VaultIndexListener {
            statusBar.updateWidget(ID())
        })
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    private val state: VaultState get() = VaultIndexService.getInstance(project).state

    override fun getText(): String {
        val s = state
        return when {
            s.root == null -> "Vault: not set"
            s.error != null -> "Vault: ${s.name} — ${s.error}"
            s.indexing -> "Vault: ${s.name} · indexing…"
            else -> "Vault: ${s.name} · ${s.index.size} notes"
        }
    }

    override fun getTooltipText(): String {
        val s = state
        if (s.root == null) return "Click to choose an Obsidian vault"
        return "${s.root}\n${s.index.size} notes, ${s.index.tags.size} tags, ${s.index.unresolvedLinks} unresolved links"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, VaultConfigurable::class.java)
    }

    override fun dispose() {
        statusBar = null
    }
}
