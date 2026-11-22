package dev.anykeyguru.vaultbridge.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class GraphToolWindowFactory : VaultToolWindowFactory() {
    override fun createPanel(project: Project): VaultPanel = GraphPanel(project)
}

private class GraphPanel(project: Project) : VaultPanel(project) {
    private val mode = ComboBox(arrayOf("Local", "Global"))
    private val depth = ComboBox(arrayOf(1, 2, 3))
    private val canvas = GraphCanvas { openNote(project, it) }
    private var shownKey: Any? = null

    init {
        depth.selectedItem = 1
        mode.addActionListener { depth.isEnabled = isLocal; refresh(indexChanged = false) }
        depth.addActionListener { refresh(indexChanged = false) }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)))
        bar.add(mode)
        bar.add(JBLabel("Depth:"))
        bar.add(depth)
        bar.add(JButton("Fit").apply { addActionListener { canvas.resetView() } })
        toolbar = bar
        setContent(canvas)
        subscribe()
    }

    private val isLocal get() = mode.selectedIndex == 0

    override fun refresh(indexChanged: Boolean) {
        val s = vaultState
        val status = canvas.emptyText
        val current = if (fillVaultEmptyText(status, s)) currentNotePath(project, s) else {
            shownKey = null
            show(null, null)
            return
        }
        val index = s.index
        val d = depth.selectedItem as Int
        val key: Any = if (isLocal) Triple(index, current, d) else index
        if (key == shownKey) {
            canvas.setCurrent(current)
            return
        }
        shownKey = key
        if (isLocal && current == null) {
            status.text = "Open a vault note to see its local graph"
            show(null, null)
            return
        }
        status.text = "Building graph…"
        show(null, null)
        val local = isLocal
        background({
            val g = if (local) GraphBuilder.local(index, current!!, d) else GraphBuilder.global(index)
            g to ForceLayout.layout(g)
        }) { (g, layout) ->
            status.text = "No notes"
            show(g, layout)
            canvas.setCurrent(currentNotePath(project, vaultState))
        }
    }

    private fun show(g: VaultGraph?, layout: ForceLayout.Result?) {
        if (g == null) cancelBackground()
        canvas.setGraph(g, layout)
    }
}

/** Граф, нарисованный на Swing: колесо — зум, перетаскивание — панорама, клик — открыть заметку. */
private class GraphCanvas(private val onOpen: (String) -> Unit) : JComponent() {
    private var graph: VaultGraph? = null
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var current = -1
    private var hover = -1
    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var pressX = 0
    private var pressY = 0
    private var dragged = false

    val emptyText: StatusText = object : StatusText(this) {
        override fun isStatusVisible(): Boolean = (graph?.size ?: 0) == 0
    }

    init {
        isOpaque = true
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                pressX = e.x; pressY = e.y; dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!dragged && abs(e.x - pressX) + abs(e.y - pressY) < JBUI.scale(3)) return
                dragged = true
                panX += e.x - pressX; panY += e.y - pressY
                pressX = e.x; pressY = e.y
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (dragged || e.button != MouseEvent.BUTTON1) return
                val i = hit(e.x, e.y)
                if (i >= 0) graph?.let { onOpen(it.paths[i]) }
            }

            override fun mouseMoved(e: MouseEvent) = updateHover(hit(e.x, e.y))
            override fun mouseExited(e: MouseEvent) = updateHover(-1)

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val f = 1.15.pow(-e.preciseWheelRotation).coerceIn(0.2 / zoom, 30.0 / zoom)
                val cx = e.x - width / 2.0
                val cy = e.y - height / 2.0
                panX = cx - (cx - panX) * f
                panY = cy - (cy - panY) * f
                zoom *= f
                repaint()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    fun setGraph(g: VaultGraph?, layout: ForceLayout.Result?) {
        graph = g
        xs = layout?.x ?: FloatArray(0)
        ys = layout?.y ?: FloatArray(0)
        current = -1
        hover = -1
        toolTipText = null
        resetView()
    }

    fun setCurrent(path: String?) {
        val i = path?.let { graph?.indexOf(it) } ?: -1
        if (i != current) { current = i; repaint() }
    }

    fun resetView() {
        zoom = 1.0; panX = 0.0; panY = 0.0
        repaint()
    }

    private val fit get() = min(width, height) - JBUI.scale(40).toDouble()
    private fun sx(i: Int) = width / 2.0 + panX + (xs[i] - 0.5) * fit * zoom
    private fun sy(i: Int) = height / 2.0 + panY + (ys[i] - 0.5) * fit * zoom

    private fun radius(i: Int): Double {
        val g = graph ?: return 0.0
        return JBUI.scale(3) + min(sqrt(g.degree[i].toDouble()), 6.0) * JBUI.scale(1) * zoom.coerceIn(1.0, 3.0).let(::sqrt)
    }

    private fun hit(x: Int, y: Int): Int {
        val g = graph ?: return -1
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until g.size) {
            val dx = sx(i) - x
            val dy = sy(i) - y
            val d = dx * dx + dy * dy
            val r = radius(i) + JBUI.scale(3)
            if (d <= r * r && d < bestD) { best = i; bestD = d }
        }
        return best
    }

    private fun updateHover(i: Int) {
        if (i == hover) return
        hover = i
        val g = graph
        toolTipText = if (i >= 0 && g != null) "<html><b>${esc(g.titles[i])}</b><br>${esc(g.paths[i])}</html>" else null
        cursor = if (i >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        try {
            g.color = UIUtil.getPanelBackground()
            g.fillRect(0, 0, width, height)
            val graph = graph
            if (graph == null || graph.size == 0) {
                emptyText.paint(this, g)
                return
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val focus = if (hover >= 0) hover else current

            val edges = Path2D.Double()
            val hot = Path2D.Double()
            for (e in graph.edgeFrom.indices) {
                val a = graph.edgeFrom[e]
                val b = graph.edgeTo[e]
                val p = if (a == focus || b == focus) hot else edges
                p.moveTo(sx(a), sy(a)); p.lineTo(sx(b), sy(b))
            }
            g.stroke = BasicStroke(JBUIScale.scale(1f))
            g.color = EDGE
            g.draw(edges)
            g.stroke = BasicStroke(JBUIScale.scale(1.5f))
            g.color = EDGE_HOT
            g.draw(hot)

            val circle = Ellipse2D.Double()
            for (i in 0 until graph.size) {
                val x = sx(i)
                val y = sy(i)
                val r = radius(i)
                if (x < -r || y < -r || x > width + r || y > height + r) continue
                circle.setFrame(x - r, y - r, 2 * r, 2 * r)
                g.color = when (i) {
                    current -> CURRENT
                    hover -> HOVER
                    else -> NODE
                }
                g.fill(circle)
            }

            g.font = JBUI.Fonts.smallFont()
            val labelsAll = graph.size <= 60 || zoom >= 2.5
            val fm = g.fontMetrics
            for (i in 0 until graph.size) {
                if (!labelsAll && i != current && i != hover) continue
                val x = sx(i)
                val y = sy(i)
                if (x < -200 || y < -50 || x > width + 200 || y > height + 50) continue
                val text = graph.titles[i].let { if (it.length > 40) it.take(39) + "…" else it }
                g.color = if (i == current || i == hover) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                g.drawString(text, (x - fm.stringWidth(text) / 2.0).toFloat(), (y + radius(i) + fm.ascent + JBUI.scale(1)).toFloat())
            }
        } finally {
            g.dispose()
        }
    }

    companion object {
        private val EDGE = JBColor(0xD3D5DB, 0x43454A)
        private val EDGE_HOT = JBColor(0x8C93A6, 0x80869A)
        private val NODE = JBColor(0x8C93A6, 0x9DA3B4)
        private val CURRENT = JBColor(0x3574F0, 0x548AF7)
        private val HOVER = JBColor(0xE08855, 0xE08855)
    }
}
