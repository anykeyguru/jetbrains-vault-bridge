package dev.anykeyguru.vaultbridge.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.anykeyguru.vaultbridge.config.Durations
import dev.anykeyguru.vaultbridge.config.GitSyncConfig
import dev.anykeyguru.vaultbridge.config.VaultConfigService
import dev.anykeyguru.vaultbridge.config.VaultGitSync
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import java.io.File

class VaultConfigurable(private val project: Project) : BoundConfigurable("Vault Bridge") {
    private val settings get() = VaultSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        val config = VaultConfigService.getInstance(project)
        val fromFile = config.vaultRoot()
        if (fromFile != null) {
            // .vaultbridge.json главнее настроек: показываем путь только для чтения, настройку не трогаем
            row("Vault folder:") {
                textField().applyToComponent { text = fromFile.toString() }.enabled(false).align(AlignX.FILL)
                    .comment("Set by <code>.vaultbridge.json</code> in the project root" + gitSummary(config.gitConfig()))
            }
            row { link("Open .vaultbridge.json") { config.openConfigFile() } }
        } else row("Vault folder:") {
            textFieldWithBrowseButton(
                fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Obsidian Vault"),
                project = project,
            )
                .bindText({ settings.vaultPath.orEmpty() }, { settings.vaultPath = it.trim().ifEmpty { null } })
                .align(AlignX.FILL)
                .comment("Folder with your notes; usually contains <code>.obsidian/</code>. Stored per project.")
                .validationOnInput { field ->
                    val path = field.text.trim()
                    when {
                        path.isEmpty() -> null
                        !File(path).isDirectory -> error("Folder not found")
                        !File(path, ".obsidian").isDirectory -> warning("No .obsidian folder: not an Obsidian vault?")
                        else -> null
                    }
                }
        }
        row {
            browserLink("Quick start guide", dev.anykeyguru.vaultbridge.startup.Onboarding.GUIDE_URL)
            comment("Type <code>[[</code> in a code comment to link a note; Cmd/Ctrl+Click the link to open it.")
        }
        row {
            checkBox("Show gutter icons for [[wiki-links]] in code comments")
                .bindSelected({ settings.showGutterIcons }, { settings.showGutterIcons = it })
        }
    }

    private fun gitSummary(git: GitSyncConfig?): String = when {
        git == null || (!git.autoCommit && !git.push) -> ". Git sync: off."
        else -> ". Git sync: " + listOfNotNull(
            if (git.autoCommit) "commit every ${Durations.format(git.commitInterval)}" else null,
            if (git.push) "push every ${Durations.format(git.pushInterval)}" else null,
        ).joinToString(", ") + "."
    }

    override fun apply() {
        val before = settings.vaultPath
        super.apply()
        if (settings.vaultPath != before) {
            VaultIndexService.getInstance(project).reindex()
            VaultGitSync.getInstance(project).restart() // .vaultbridge.json без "vault" берёт путь из настроек
        }
    }
}
