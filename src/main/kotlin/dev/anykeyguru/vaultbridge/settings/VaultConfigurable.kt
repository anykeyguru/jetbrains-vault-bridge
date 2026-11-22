package dev.anykeyguru.vaultbridge.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import java.io.File

class VaultConfigurable(private val project: Project) : BoundConfigurable("Vault Bridge") {
    private val settings get() = VaultSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        row("Vault folder:") {
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
            checkBox("Show gutter icons for [[wiki-links]] in code comments")
                .bindSelected({ settings.showGutterIcons }, { settings.showGutterIcons = it })
        }
    }

    override fun apply() {
        val before = settings.vaultPath
        super.apply()
        if (settings.vaultPath != before) VaultIndexService.getInstance(project).reindex()
    }
}
