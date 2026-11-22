package dev.anykeyguru.vaultbridge.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.anykeyguru.vaultbridge.index.VaultIndexService

class ReindexVaultAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        VaultIndexService.getInstance(e.project ?: return).reindex()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && VaultIndexService.getInstance(project).configuredRoot() != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
