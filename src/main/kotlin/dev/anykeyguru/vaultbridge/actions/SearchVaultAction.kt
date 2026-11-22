package dev.anykeyguru.vaultbridge.actions

import com.intellij.openapi.project.Project
import dev.anykeyguru.vaultbridge.search.VaultSearchPopup
import java.nio.file.Path

class SearchVaultAction : VaultAction() {
    override fun perform(project: Project, root: Path) = VaultSearchPopup(project).show()
}
