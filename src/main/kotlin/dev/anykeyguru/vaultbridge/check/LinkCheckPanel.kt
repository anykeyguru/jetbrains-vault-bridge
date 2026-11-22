package dev.anykeyguru.vaultbridge.check

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.settings.VaultConfigurable
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Tool window скрыт, пока проверку не запустили хотя бы раз. */
class LinkCheckToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LinkCheckPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private data class Group(val problem: LinkProblem, val count: Int)

internal class LinkCheckPanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable {
    private val service = LinkCheckService.getInstance(project)
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model)
    private val summary = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
            }
        })

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("VaultBridge.LinkCheck", DefaultActionGroup(RerunAction()), false)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val body = JPanel(BorderLayout())
        body.add(summary, BorderLayout.NORTH)
        body.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        setContent(body)

        service.panel = this
        val last = service.lastResult
        if (last != null) show(last) else {
            summary.text = ""
            tree.emptyText.text = "Run Tools | Vault Bridge | Check Note Links in Project"
        }
    }

    fun setRunning(running: Boolean) {
        // После отмены возвращаем сводку прошлого прогона.
        summary.text = if (running) "Checking note links…" else service.lastResult?.let(LinkCheck::summary).orEmpty()
    }

    fun show(r: LinkCheckResult) {
        summary.text = LinkCheck.summary(r)
        val root = DefaultMutableTreeNode()
        tree.emptyText.clear()
        if (r.vaultMissing) {
            tree.emptyText.appendLine("Vault folder not found: ${r.vaultPath}")
            tree.emptyText.appendLine("Links in project files could not be verified.")
            tree.emptyText.appendLine("Fix the path in Settings | Tools | Vault Bridge (or in .vaultbridge.json)", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VaultConfigurable::class.java)
            }
        } else {
            tree.emptyText.text = LinkCheck.okMessage(r)
            for ((problem, rows) in LinkCheck.groups(r)) {
                val group = DefaultMutableTreeNode(Group(problem, rows.size))
                rows.forEach { group.add(DefaultMutableTreeNode(it)) }
                root.add(group)
            }
        }
        model.setRoot(root)
        TreeUtil.expandAll(tree)
    }

    private fun openSelected(): Boolean {
        val row = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? BrokenLink ?: return false
        val file = LocalFileSystem.getInstance().findFileByPath(row.filePath) ?: return false
        OpenFileDescriptor(project, file, row.offset).navigate(true)
        return true
    }

    override fun dispose() {
        if (service.panel === this) service.panel = null
    }

    private inner class RerunAction : DumbAwareAction("Re-run", "Check note links in project files again", AllIcons.Actions.Rerun) {
        override fun actionPerformed(e: AnActionEvent) = service.run()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.isRunning && VaultIndexService.getInstance(project).configuredRoot() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Group -> {
                    append(item.problem.group, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is BrokenLink -> {
                    append("${item.fileLabel}:${item.line + 1}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${item.link}", SimpleTextAttributes.LINK_BOLD_ATTRIBUTES)
                    append("  — ${item.reason}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
