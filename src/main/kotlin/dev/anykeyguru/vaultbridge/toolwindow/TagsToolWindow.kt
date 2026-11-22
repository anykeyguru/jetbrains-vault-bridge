package dev.anykeyguru.vaultbridge.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.anykeyguru.vaultbridge.model.Note
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class TagsToolWindowFactory : VaultToolWindowFactory() {
    override fun createPanel(project: Project): VaultPanel = TagsPanel(project)
}

private data class TagItem(val node: TagNode) {
    override fun toString(): String = "#" + node.name
}

private data class NoteItem(val note: Note, val tag: String) {
    override fun toString(): String = note.title
}

private class TagsPanel(project: Project) : VaultPanel(project) {
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model)
    private var shownIndex: Any? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        TreeSpeedSearch.installOn(tree, true) { path -> (path.lastPathComponent as DefaultMutableTreeNode).userObject?.toString() }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
            }
        })
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        subscribe()
    }

    // Дерево тегов не зависит от текущей вкладки — перестраиваем только на новый снимок индекса.
    override fun refresh(indexChanged: Boolean) {
        val s = vaultState
        if (!fillVaultEmptyText(tree.emptyText, s)) {
            shownIndex = null
            model.setRoot(DefaultMutableTreeNode())
            return
        }
        if (s.index === shownIndex) return
        shownIndex = s.index
        tree.emptyText.text = "No tags in vault"

        val expanded = HashSet<String>()
        TreeUtil.collectExpandedPaths(tree).forEach { p -> keyOf(p)?.let(expanded::add) }
        val selected = tree.selectionPath?.let(::keyOf)

        val root = DefaultMutableTreeNode()
        TagNode.build(s.index.tags).children.values.forEach { root.add(toTreeNode(it)) }
        model.setRoot(root)

        TreeUtil.treeNodeTraverser(root).forEach { n ->
            val path = TreePath((n as DefaultMutableTreeNode).path)
            val key = keyOf(path) ?: return@forEach
            if (key in expanded) tree.expandPath(path)
            if (key == selected) tree.selectionPath = path
        }
    }

    private fun keyOf(path: TreePath): String? = when (val item = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
        is TagItem -> "#" + item.node.fullTag
        is NoteItem -> item.tag + "|" + item.note.path
        else -> null
    }

    private fun toTreeNode(tag: TagNode): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(TagItem(tag))
        tag.children.values.forEach { node.add(toTreeNode(it)) }
        tag.notes.forEach { node.add(DefaultMutableTreeNode(NoteItem(it, tag.fullTag))) }
        return node
    }

    private fun openSelected(): Boolean {
        val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? NoteItem ?: return false
        openNote(project, item.note.path)
        return true
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is TagItem -> {
                    append("#" + item.node.name)
                    append("  ${item.node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is NoteItem -> {
                    append(item.note.title)
                    if (item.note.folder.isNotEmpty()) append("  ${item.note.folder}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
