package dev.anykeyguru.vaultbridge.actions

import dev.anykeyguru.vaultbridge.index.VaultIndex
import dev.anykeyguru.vaultbridge.index.VaultState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Текстовый отчёт о состоянии vault (чистая функция от снимка индекса). */
object VaultDiagnostics {
    private const val TOP = 20

    /** Неразрешённые цели ссылок: цель (как написана впервые) → число ссылок, по убыванию. */
    fun unresolvedTargets(index: VaultIndex): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (note in index.notes.values) for (link in note.links) {
            if (link.target.isEmpty() || index.resolve(link.target, note.path) != null) continue
            val key = link.target.trim().lowercase()
            val prev = counts[key]
            counts[key] = (prev?.first ?: link.target.trim()) to ((prev?.second ?: 0) + 1)
        }
        return counts.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    }

    /** Имя → пути заметок с одинаковым именем файла (Obsidian резолвит такие ссылки неоднозначно). */
    fun duplicateNames(index: VaultIndex): Map<String, List<String>> =
        index.notes.values.groupBy { it.name.lowercase() }
            .filterValues { it.size > 1 }
            .mapValues { (_, notes) -> notes.map { it.path }.sorted() }
            .toSortedMap()

    /**
     * [notIndexed] — `.md` файлы на диске, которых нет в индексе (ошибка чтения или изменение в процессе);
     * null — не проверялось. [lastIndexedAt] — epoch ms или 0.
     */
    fun report(state: VaultState, notIndexed: List<String>?, lastIndexedAt: Long): String = buildString {
        val index = state.index
        appendLine("Vault:            ${state.root ?: "not configured"}")
        state.error?.let { appendLine("Error:            $it") }
        appendLine("Status:           ${if (state.indexing) "indexing…" else "ready"}")
        appendLine("Last index:       ${if (lastIndexedAt > 0) TIME.format(Instant.ofEpochMilli(lastIndexedAt)) else "n/a (before diagnostics started watching)"}")
        appendLine("Notes:            ${index.size}")
        appendLine("Tags:             ${index.tags.size}")
        appendLine("Unresolved links: ${index.unresolvedLinks}")

        val unresolved = unresolvedTargets(index)
        if (unresolved.isNotEmpty()) {
            appendLine()
            appendLine("Top unresolved targets (${unresolved.size} distinct):")
            unresolved.take(TOP).forEach { (target, n) -> appendLine("  %4d  [[%s]]".format(n, target)) }
            if (unresolved.size > TOP) appendLine("  … and ${unresolved.size - TOP} more")
        }

        val dups = duplicateNames(index)
        appendLine()
        appendLine("Duplicate note names: ${dups.size}")
        dups.entries.take(TOP).forEach { (_, paths) -> appendLine("  ${paths.joinToString("  |  ")}") }
        if (dups.size > TOP) appendLine("  … and ${dups.size - TOP} more")

        appendLine()
        when {
            notIndexed == null -> appendLine("Unreadable notes: not checked")
            else -> {
                appendLine("Unreadable / not indexed notes: ${notIndexed.size}")
                notIndexed.take(TOP).forEach { appendLine("  $it") }
                if (notIndexed.size > TOP) appendLine("  … and ${notIndexed.size - TOP} more")
            }
        }
    }

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
}
