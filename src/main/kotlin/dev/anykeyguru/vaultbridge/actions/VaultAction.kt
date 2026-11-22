package dev.anykeyguru.vaultbridge.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import java.nio.file.Path

/** Общая база: действие доступно только при заданном vault, иначе — подсказка в описании. */
abstract class VaultAction : DumbAwareAction() {
    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = VaultIndexService.getInstance(project).configuredRoot() ?: return
        perform(project, root)
    }

    protected abstract fun perform(project: Project, root: Path)

    override fun update(e: AnActionEvent) {
        val project = e.project
        val configured = project != null && VaultIndexService.getInstance(project).configuredRoot() != null
        e.presentation.isEnabled = configured
        e.presentation.description = if (configured) templatePresentation.description else NO_VAULT_HINT
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        const val NO_VAULT_HINT = "Choose an Obsidian vault in Settings | Tools | Vault Bridge first"
    }
}
