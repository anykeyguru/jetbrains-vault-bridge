package dev.anykeyguru.vaultbridge.search

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.anykeyguru.vaultbridge.actions.NoteFiles
import dev.anykeyguru.vaultbridge.index.VaultIndexService
import dev.anykeyguru.vaultbridge.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Попап поиска по vault: метаданные фильтруются сразу на EDT (индекс в памяти),
 * содержимое — в фоне с отменой при каждом новом вводе.
 */
class VaultSearchPopup(private val project: Project) {
    private val indexService = VaultIndexService.getInstance(project)
    private val search = VaultSearchService.getInstance(project)
    private val field = SearchTextField(false)
    private val model = CollectionListModel<SearchHit>()
    private val list = JBList(model)
    private lateinit var popup: JBPopup
    private var job: Job? = null
    private var generation = 0 // только EDT

    fun show() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.emptyText.text = "No matching notes"
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
        field.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = update()
        })
        field.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> openSelected()
                    KeyEvent.VK_DOWN -> ScrollingUtil.moveDown(list, 0)
                    KeyEvent.VK_UP -> ScrollingUtil.moveUp(list, 0)
                    KeyEvent.VK_PAGE_DOWN -> ScrollingUtil.movePageDown(list)
                    KeyEvent.VK_PAGE_UP -> ScrollingUtil.movePageUp(list)
                    else -> return
                }
                e.consume()
            }
        })
        val panel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(4)
            preferredSize = JBUI.size(680, 420)
            add(field, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field.textEditor)
            .setTitle("Search Vault")
            .setProject(project)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setDimensionServiceKey(project, "VaultBridge.SearchPopup", true)
            .createPopup()
        Disposer.register(popup) { job?.cancel() }
        update()
        popup.showCenteredInCurrentWindow(project)
    }

    private fun update() {
        val gen = ++generation
        job?.cancel()
        val state = indexService.state
        val root = state.root?.let { Paths.get(it) }
        val notes = state.index.notes.values
        val words = SearchMatcher.words(field.text)
        list.emptyText.text = when {
            root == null -> "Vault is not configured"
            state.indexing && notes.isEmpty() -> "Indexing vault…"
            else -> "No matching notes"
        }
        if (words.isEmpty()) {
            show(gen, notes.map { SearchHit(it, 0) })
            return
        }
        val hits = ArrayList<SearchHit>()
        val pending = ArrayList<Note>()
        for (note in notes) {
            val hit = SearchMatcher.match(note, words, null)
            if (hit != null) hits += hit else pending += note
        }
        show(gen, hits)
        if (root == null || pending.isEmpty()) return
        job = search.scope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MS)
            var lastPost = System.nanoTime()
            for (note in pending) {
                ensureActive()
                val content = search.content(root, note.path) ?: continue
                SearchMatcher.match(note, words, content)?.let(hits::add)
                if (System.nanoTime() - lastPost > POST_INTERVAL_NS) {
                    lastPost = System.nanoTime()
                    post(gen, ArrayList(hits))
                }
            }
            post(gen, hits)
        }
    }

    private fun post(gen: Int, hits: List<SearchHit>) =
        ApplicationManager.getApplication().invokeLater({ show(gen, hits) }, ModalityState.any())

    private fun show(gen: Int, hits: List<SearchHit>) {
        if (gen != generation || popup.isDisposed) return
        val selected = list.selectedValue?.note?.path
        val sorted = hits.sortedWith(SearchMatcher.ORDER).take(MAX_RESULTS)
        model.replaceAll(sorted)
        val idx = sorted.indexOfFirst { it.note.path == selected }.takeIf { it >= 0 } ?: 0
        if (sorted.isNotEmpty()) ScrollingUtil.selectItem(list, idx)
    }

    private fun openSelected() {
        val hit = list.selectedValue ?: return
        val root = indexService.state.root ?: return
        popup.cancel()
        NoteFiles.open(project, Path.of(root).resolve(hit.note.path))
    }

    private class Renderer : ColoredListCellRenderer<SearchHit>() {
        override fun customizeCellRenderer(
            list: JList<out SearchHit>, value: SearchHit, index: Int, selected: Boolean, hasFocus: Boolean,
        ) {
            val note = value.note
            icon = AllIcons.FileTypes.Text
            append(note.title)
            if (!note.title.equals(note.name, ignoreCase = true)) append("  ${note.name}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  " + note.folder.ifEmpty { "/" }, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            value.snippet?.let { append("   $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 120L
        const val POST_INTERVAL_NS = 150_000_000L
        const val MAX_RESULTS = 500
    }
}
