package dev.anykeyguru.vaultbridge.config

import com.google.gson.JsonPrimitive
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import dev.anykeyguru.vaultbridge.settings.VaultSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/** Tools → Vault Bridge → Create .vaultbridge.json */
class CreateConfigAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = VaultConfigService.getInstance(project)
        val file = service.configFile() ?: return
        if (Files.exists(file)) {
            service.openConfigFile()
            return
        }
        val projectRoot = file.parent
        val vault = VaultSettings.getInstance(project).state.vaultPath?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { ConfigParser.suggestVaultValue(projectRoot, Paths.get(it)) }
        try {
            Files.writeString(file, ConfigTemplate.render(vault))
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Cannot write $file: ${ex.message}", "Vault Bridge")
            return
        }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
        offerLocalExclude(project, projectRoot)
        service.openConfigFile()
    }

    private fun offerLocalExclude(project: Project, projectRoot: Path) {
        if (!Files.exists(projectRoot.resolve(".git"))) return
        val answer = Messages.showYesNoDialog(
            project,
            "Keep this file local (add to .git/info/exclude)?\n\n" +
                "Choose Yes if this is someone else's repository or the vault path is personal.",
            "Vault Bridge",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        val exclude = ProgressManager.getInstance().runProcessWithProgressSynchronously<Path?, Exception>(
            { excludeFile(projectRoot) }, "Locating .git/info/exclude", false, project,
        ) ?: run {
            Messages.showErrorDialog(project, "Cannot find .git/info/exclude for this project", "Vault Bridge")
            return
        }
        try {
            ExcludeFile.addEntry(exclude, ConfigParser.FILE_NAME)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Cannot update $exclude: ${ex.message}", "Vault Bridge")
        }
    }

    /** `git rev-parse --git-path info/exclude` понимает worktree и submodule; иначе — `.git/info/exclude`. */
    private fun excludeFile(projectRoot: Path): Path? {
        val r = SystemGit.run(projectRoot, listOf("rev-parse", "--git-path", "info/exclude"), Duration.ofSeconds(10))
        if (r.ok && r.stdout.isNotBlank()) return projectRoot.resolve(r.stdout.trim()).normalize()
        val dotGit = projectRoot.resolve(".git")
        return if (Files.isDirectory(dotGit)) dotGit.resolve("info/exclude") else null
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** Tools → Vault Bridge → Commit Vault Now */
class CommitVaultNowAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        VaultGitSync.getInstance(e.project ?: return).commitNow()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && VaultGitSync.getInstance(project).repoLikely()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

object ExcludeFile {
    /** Дописывает строку в `.git/info/exclude`, если её там ещё нет. Возвращает true, если файл изменён. */
    fun addEntry(exclude: Path, entry: String): Boolean {
        val existing = if (Files.exists(exclude)) Files.readString(exclude) else ""
        if (existing.lines().any { it.trim() == entry || it.trim() == "/$entry" }) return false
        Files.createDirectories(exclude.parent)
        val prefix = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        Files.writeString(exclude, existing + prefix + entry + "\n")
        return true
    }
}

object ConfigTemplate {
    fun render(vault: String?): String {
        val vaultJson = JsonPrimitive(vault ?: "").toString()
        return """
            |{
            |  // Vault Bridge settings for this project. Comments and trailing commas are allowed.
            |  // While this file exists, it overrides the vault folder from Settings | Tools | Vault Bridge.
            |
            |  // Obsidian vault folder: relative to this project, absolute, or ~/...
            |  "vault": $vaultJson,
            |
            |  // Remove the whole "git" block to turn git sync off.
            |  "git": {
            |    // Commit changes in the vault automatically (only if the vault is a git repository).
            |    "autoCommit": true,
            |    // How often to look for changes: 30s, 5m, 1h, 1h30m. Minimum 30s.
            |    "commitInterval": "5m",
            |    // Push commits to the vault's existing remote. Never adds remotes, never force-pushes.
            |    "push": false,
            |    // How often to push. Minimum 1m.
            |    "pushInterval": "30m",
            |    // Optional: where to clone the vault from if the folder above does not exist.
            |    // "remote": "git@github.com:me/notes.git",
            |  },
            |}
            |""".trimMargin()
    }
}
