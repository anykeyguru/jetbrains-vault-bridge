package dev.anykeyguru.vaultbridge.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.anykeyguru.vaultbridge.model.Note
import dev.anykeyguru.vaultbridge.model.WikiLink
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class BacklinksToolWindowFactory : VaultToolWindowFactory() {
    override fun createPanel(project: Project): VaultPanel = BacklinksPanel(project)
}

/** Строка контекста вокруг ссылки: [before] + [link] + [after]. */
data class LinkContext(val line: Int, val before: String, val link: String, val after: String) {
    companion object {
        private const val SIDE = 80

        /** [start]/[end] — смещения ссылки в [text]. */
        fun of(text: String, start: Int, end: Int): LinkContext {
            val s = start.coerceIn(0, text.length)
            val e = end.coerceIn(s, text.length)
            val lineStart = text.lastIndexOf('\n', s - 1) + 1
            val lineEnd = text.indexOf('\n', e).let { if (it < 0) text.length else it }
            val line = (0 until s).count { text[it] == '\n' }
            var before = text.substring(lineStart, s).trimStart()
            var after = text.substring(e, lineEnd).trimEnd()
            if (before.length > SIDE) before = "…" + before.takeLast(SIDE)
            if (after.length > SIDE) after = after.take(SIDE) + "…"
            return LinkContext(line, before, text.substring(s, e), after)
        }
    }
}

private sealed interface Item
private data class Section(val title: String, val count: Int) : Item
private data class Source(val note: Note, val mentions: List<Mention>) : Item
private class Mention(val note: Note, val link: WikiLink) : Item {
    @Volatile var context: LinkContext? = null
}
private data class Outgoing(val target: String, val note: Note?) : Item

private class BacklinksPanel(project: Project) : VaultPanel(project) {
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model)
    private var shownKey: Any? = null

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
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        subscribe()
    }

    override fun refresh(indexChanged: Boolean) {
        val s = vaultState
        val path = currentNoteOrEmpty(tree.emptyText, "Open a vault note to see its backlinks")
        val key = path?.let { it to s.index }
        if (key == shownKey && path != null) return
        shownKey = key
        val root = DefaultMutableTreeNode()
        cancelBackground()
        if (path == null) {
            model.setRoot(root)
            return
        }
        val index = s.index
        val note = index.notes.getValue(path)

        val mentions = index.backlinksTo(path).map { Mention(it.source, it.link) }
        val bySource = mentions.groupBy { it.note.path }.values
            .map { Source(it.first().note, it.sortedBy { m -> m.link.start }) }
            .sortedBy { it.note.title.lowercase() }
        val back = DefaultMutableTreeNode(Section("Linked mentions", mentions.size))
        for (src in bySource) {
            val srcNode = DefaultMutableTreeNode(src)
            src.mentions.forEach { srcNode.add(DefaultMutableTreeNode(it)) }
            back.add(srcNode)
        }
        root.add(back)

        val outgoing = note.links.filter { it.target.isNotEmpty() }
            .distinctBy { it.target.trim().lowercase() }
            .map { Outgoing(it.target, index.resolve(it.target, path)) }
            .sortedWith(compareBy({ it.note == null }, { (it.note?.title ?: it.target).lowercase() }))
        val out = DefaultMutableTreeNode(Section("Outgoing links", outgoing.size))
        outgoing.forEach { out.add(DefaultMutableTreeNode(it)) }
        root.add(out)

        model.setRoot(root)
        TreeUtil.expandAll(tree)
        if (mentions.isNotEmpty()) loadContexts(s.root!!, mentions)
    }

    /** Читает исходники бэклинков с диска в пуле и дорисовывает строки контекста. */
    private fun loadContexts(root: String, mentions: List<Mention>) {
        background({
            for ((path, group) in mentions.groupBy { it.note.path }) {
                val text = readText(Paths.get(root).resolve(path).toString()) ?: continue
                group.forEach { it.context = LinkContext.of(text, it.link.start, it.link.end) }
            }
        }) { tree.repaint() }
    }

    private fun readText(file: String): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get(file)))).toString()
    } catch (_: Exception) {
        null
    }

    private fun openSelected(): Boolean {
        val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Item ?: return false
        when (item) {
            is Mention -> open(item)
            is Source -> open(item.mentions.first())
            is Outgoing -> openNote(project, item.note?.path ?: return false)
            is Section -> return false
        }
        return true
    }

    private fun open(m: Mention) {
        val ctx = m.context
        if (ctx != null) openNote(project, m.note.path, line = ctx.line) else openNote(project, m.note.path, offset = m.link.start)
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Section -> {
                    append(item.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Source -> {
                    append(item.note.title)
                    if (item.note.folder.isNotEmpty()) append("  ${item.note.folder}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Mention -> {
                    val ctx = item.context
                    if (ctx == null) {
                        append("…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        append(ctx.before)
                        append(ctx.link, SimpleTextAttributes.LINK_BOLD_ATTRIBUTES)
                        append(ctx.after)
                        append("  :${ctx.line + 1}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                is Outgoing -> {
                    val n = item.note
                    if (n == null) {
                        append(item.target, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append("  unresolved", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    } else {
                        append(n.title)
                        append("  ${n.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
    }
}
