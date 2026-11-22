package dev.anykeyguru.vaultbridge.check

import com.intellij.openapi.project.Project
import dev.anykeyguru.vaultbridge.actions.VaultAction
import java.nio.file.Path

/** Tools | Vault Bridge | Check Note Links in Project. */
class CheckNoteLinksAction : VaultAction() {
    override fun perform(project: Project, root: Path) = LinkCheckService.getInstance(project).run()
}
