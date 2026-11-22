package dev.anykeyguru.vaultbridge.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import java.nio.file.Path

/** Новая заметка `<vault>/<имя>.md`; `/` в имени задаёт подпапку. Существующая просто открывается. */
class NewNoteAction : VaultAction() {
    override fun perform(project: Project, root: Path) {
        val input = Messages.showInputDialog(
            project,
            "Note name (use / for subfolders):",
            "New Vault Note",
            null,
            "",
            object : InputValidator {
                override fun checkInput(inputString: String?) = NoteNames.toRelativePath(inputString.orEmpty()) != null
                override fun canClose(inputString: String?) = checkInput(inputString)
            },
        ) ?: return
        val rel = NoteNames.toRelativePath(input) ?: return
        NoteFiles.runAndOpen(project, "Creating note") {
            NoteFiles.createIfMissing(root.resolve(rel)) { "# ${NoteNames.title(rel)}\n\n" }
        }
    }
}
