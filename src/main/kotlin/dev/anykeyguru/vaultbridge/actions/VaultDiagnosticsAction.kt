package dev.anykeyguru.vaultbridge.actions

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.search.VaultSearchService
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

class VaultDiagnosticsAction : VaultAction() {
    override fun perform(project: Project, root: Path) {
        object : Task.Backgroundable(project, "Collecting vault diagnostics", true) {
            private var text = ""

            override fun run(indicator: ProgressIndicator) {
                val state = VaultIndexService.getInstance(project).state
                // Файлы на диске, которых нет в индексе, — индексатор пропускает нечитаемые с warn в лог.
                val notIndexed = if (state.indexing || state.root == null || !Files.isDirectory(root)) null else
                    Files.walk(root).use { s ->
                        s.asSequence()
                            .onEach { indicator.checkCanceled() }
                            .filter { it.isRegularFile() && it.name.endsWith(".md") && VaultIndexService.isVisible(root, it) }
                            .map { VaultIndexService.relPath(root, it) }
                            .filter { it !in state.index.notes }
                            .sorted()
                            .toList()
                    }
                text = VaultDiagnostics.report(state, notIndexed, VaultSearchService.getInstance(project).lastIndexedAt)
            }

            override fun onSuccess() {
                if (!project.isDisposed) ReportDialog(project, text).show()
            }
        }.queue()
    }

    private class ReportDialog(project: Project, private val text: String) : DialogWrapper(project) {
        init {
            title = "Vault Diagnostics"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val area = JBTextArea(text).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)
                caretPosition = 0
            }
            return JBScrollPane(area).apply { preferredSize = JBUI.size(720, 460) }
        }

        override fun createActions(): Array<Action> = arrayOf(okAction)
    }
}
