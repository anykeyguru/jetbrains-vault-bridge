package dev.anykeyguru.vaultbridge.toolwindow

import dev.anykeyguru.vaultbridge.index.VaultIndex
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Неориентированный граф заметок: узлы — пути, рёбра — пары индексов без дублей и петель. */
class VaultGraph(val paths: List<String>, val titles: List<String>, val edgeFrom: IntArray, val edgeTo: IntArray) {
    val size: Int get() = paths.size
    val degree: IntArray = IntArray(paths.size).also { d -> for (i in edgeFrom.indices) { d[edgeFrom[i]]++; d[edgeTo[i]]++ } }
    fun indexOf(path: String): Int = paths.indexOf(path)
}

object GraphBuilder {
    /** Резолвленные исходящие ссылки + бэклинки — соседи заметки в графе. */
    fun neighbours(index: VaultIndex, path: String): Set<String> {
        val out = LinkedHashSet<String>()
        index.notes[path]?.links?.forEach { link ->
            if (link.target.isNotEmpty()) index.resolve(link.target, path)?.let { out += it.path }
        }
        index.backlinksTo(path).forEach { out += it.source.path }
        out -= path
        return out
    }

    /** BFS от [center] на глубину [depth]. */
    fun neighbourhood(index: VaultIndex, center: String, depth: Int): Set<String> {
        if (center !in index.notes) return emptySet()
        val seen = linkedSetOf(center)
        var frontier = listOf(center)
        repeat(depth) {
            frontier = frontier.flatMap { neighbours(index, it) }.filter { seen.add(it) }
            if (frontier.isEmpty()) return seen
        }
        return seen
    }

    fun local(index: VaultIndex, center: String, depth: Int): VaultGraph =
        build(index, neighbourhood(index, center, depth).sorted(), Int.MAX_VALUE)

    fun global(index: VaultIndex, maxEdges: Int = 8000): VaultGraph = build(index, index.notes.keys.sorted(), maxEdges)

    private fun build(index: VaultIndex, paths: List<String>, maxEdges: Int): VaultGraph {
        val pos = paths.withIndex().associate { it.value to it.index }
        val seen = HashSet<Long>()
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        loop@ for ((i, path) in paths.withIndex()) {
            for (link in index.notes[path]?.links.orEmpty()) {
                if (link.target.isEmpty()) continue
                val j = index.resolve(link.target, path)?.path?.let(pos::get) ?: continue
                if (j == i) continue
                val key = min(i, j).toLong() shl 32 or max(i, j).toLong()
                if (!seen.add(key)) continue
                from += i; to += j
                if (from.size >= maxEdges) break@loop
            }
        }
        val titles = paths.map { index.notes[it]?.title ?: it }
        return VaultGraph(paths, titles, from.toIntArray(), to.toIntArray())
    }
}

/**
 * Fruchterman–Reingold с детерминированным seed. Для больших графов отталкивание считается
 * только между соседними клетками сетки (O(n) вместо O(n²)). Результат нормализован в [0,1]².
 */
object ForceLayout {
    class Result(val x: FloatArray, val y: FloatArray)

    fun layout(graph: VaultGraph, seed: Long = 42L, maxIterations: Int = if (graph.size > 1000) 120 else 250): Result {
        val n = graph.size
        if (n == 0) return Result(FloatArray(0), FloatArray(0))
        if (n == 1) return Result(floatArrayOf(0.5f), floatArrayOf(0.5f))
        val rnd = Random(seed)
        val side = sqrt(n.toDouble()) * 1.5
        val x = DoubleArray(n) { rnd.nextDouble() * side }
        val y = DoubleArray(n) { rnd.nextDouble() * side }
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        val k = 1.0
        val useGrid = n > 300
        val cell = 2 * k
        var temp = side / 8
        val cooling = temp / (maxIterations + 1)
        val center = side / 2

        for (iter in 0 until maxIterations) {
            dx.fill(0.0); dy.fill(0.0)
            fun repel(i: Int, j: Int) {
                var ddx = x[i] - x[j]
                var ddy = y[i] - y[j]
                var d2 = ddx * ddx + ddy * ddy
                if (d2 < 1e-9) { ddx = 0.01 * ((i - j) % 7 + 1); ddy = 0.01; d2 = ddx * ddx + ddy * ddy }
                if (useGrid && d2 > cell * cell) return
                val f = k * k / d2 // = (k²/d) / d — сразу нормируем вектор
                dx[i] += ddx * f; dy[i] += ddy * f
                dx[j] -= ddx * f; dy[j] -= ddy * f
            }
            if (useGrid) {
                val buckets = HashMap<Long, MutableList<Int>>()
                for (i in 0 until n) buckets.getOrPut(cellKey(x[i], y[i], cell)) { ArrayList() } += i
                for ((key, list) in buckets) {
                    val cx = (key shr 32).toInt()
                    val cy = key.toInt()
                    for (ox in -1..1) for (oy in -1..1) {
                        val other = buckets[(cx + ox).toLong() shl 32 or ((cy + oy).toLong() and 0xffffffffL)] ?: continue
                        for (i in list) for (j in other) if (i < j) repel(i, j)
                    }
                }
            } else {
                for (i in 0 until n) for (j in i + 1 until n) repel(i, j)
            }
            for (e in graph.edgeFrom.indices) {
                val a = graph.edgeFrom[e]
                val b = graph.edgeTo[e]
                val ddx = x[a] - x[b]
                val ddy = y[a] - y[b]
                val d = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(1e-6)
                val f = d / k // = (d²/k) / d
                dx[a] -= ddx * f; dy[a] -= ddy * f
                dx[b] += ddx * f; dy[b] += ddy * f
            }
            for (i in 0 until n) {
                // слабая гравитация к центру держит несвязные компоненты рядом
                dx[i] += (center - x[i]) * 0.05
                dy[i] += (center - y[i]) * 0.05
                val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (len > 0) {
                    val step = min(len, temp)
                    x[i] += dx[i] / len * step
                    y[i] += dy[i] / len * step
                }
            }
            temp = max(temp - cooling, 0.01)
        }
        return normalize(x, y)
    }

    private fun cellKey(x: Double, y: Double, cell: Double): Long {
        val cx = Math.floor(x / cell).toInt()
        val cy = Math.floor(y / cell).toInt()
        return cx.toLong() shl 32 or (cy.toLong() and 0xffffffffL)
    }

    private fun normalize(x: DoubleArray, y: DoubleArray): Result {
        val minX = x.min(); val maxX = x.max(); val minY = y.min(); val maxY = y.max()
        val range = max(maxX - minX, maxY - minY).takeIf { it > 1e-9 } ?: 1.0
        val offX = (1 - (maxX - minX) / range) / 2
        val offY = (1 - (maxY - minY) / range) / 2
        return Result(
            FloatArray(x.size) { ((x[it] - minX) / range + offX).toFloat() },
            FloatArray(y.size) { ((y[it] - minY) / range + offY).toFloat() },
        )
    }
}
