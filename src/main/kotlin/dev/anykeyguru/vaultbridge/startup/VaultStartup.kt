package dev.anykeyguru.vaultbridge.startup

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.anykeyguru.vaultbridge.VaultBridgeIcons
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.settings.VaultConfigurable

class VaultStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = VaultIndexService.getInstance(project)
        service.reindex()
        if (service.configuredRoot() == null) Onboarding.suggestVault(project)
    }
}

/** Подсказка для проекта без vault: человек только поставил плагин и не знает, с чего начать. */
object Onboarding {
    const val GUIDE_URL = "https://github.com/anykeyguru/jetbrains-vault-bridge#quick-start"
    private const val DISMISSED_KEY = "dev.anykeyguru.vaultbridge.onboarding.dismissed"

    fun suggestVault(project: Project) {
        val props = PropertiesComponent.getInstance(project)
        if (props.getBoolean(DISMISSED_KEY)) return
        NotificationGroupManager.getInstance().getNotificationGroup("Vault Bridge")
            .createNotification(
                "Connect your Obsidian vault",
                "Choose the vault folder for this project to get <code>[[wiki-link]]</code> completion, " +
                    "navigation from code comments, search, backlinks and graph.",
                NotificationType.INFORMATION,
            )
            .setIcon(VaultBridgeIcons.Vault)
            .addAction(NotificationAction.createSimpleExpiring("Choose vault…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VaultConfigurable::class.java)
            })
            .addAction(NotificationAction.createSimple("Quick start guide") { BrowserUtil.browse(GUIDE_URL) })
            .addAction(NotificationAction.createSimpleExpiring("Not for this project") {
                props.setValue(DISMISSED_KEY, true)
            })
            .notify(project)
    }
}
