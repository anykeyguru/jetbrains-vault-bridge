package dev.anykeyguru.vaultbridge.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Настройки на проект: у каждого проекта свой vault. Хранятся в workspace.xml,
 * чтобы личный путь не попал в git вместе с .idea.
 */
@Service(Service.Level.PROJECT)
@State(name = "VaultBridgeSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class VaultSettings : SimplePersistentStateComponent<VaultSettings.Options>(Options()) {
    class Options : BaseState() {
        var vaultPath by string()
        var showGutterIcons by property(true)
    }

    companion object {
        fun getInstance(project: Project): VaultSettings = project.service()
    }
}
