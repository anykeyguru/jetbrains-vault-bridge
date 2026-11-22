package dev.anykeyguru.vaultbridge.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.anykeyguru.vaultbridge.index.VaultIndexService

class VaultStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        VaultIndexService.getInstance(project).reindex()
    }
}
