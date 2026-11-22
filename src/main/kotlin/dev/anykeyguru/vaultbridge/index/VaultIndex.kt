package dev.anykeyguru.vaultbridge.index

import dev.anykeyguru.vaultbridge.model.Note
import dev.anykeyguru.vaultbridge.model.WikiLink

data class Backlink(val source: Note, val link: WikiLink)

/**
 * Неизменяемый снимок vault. Пересобирается целиком при любом изменении —
 * для тысяч заметок это миллисекунды, зато читать его можно из любого потока без блокировок.
 */
class VaultIndex(notes: Collection<Note>) {
    val notes: Map<String, Note> = notes.associateBy { it.path }

    private val byName: Map<String, List<Note>> = notes.groupBy { it.name.lowercase() }
    private val byAlias: Map<String, List<Note>> =
        notes.flatMap { n -> n.aliases.map { it.lowercase() to n } }.groupBy({ it.first }, { it.second })
    private val byPathNoExt: Map<String, Note> = notes.associateBy { it.path.removeSuffix(".md").lowercase() }

    /** Тег в нижнем регистре → заметки. */
    val tags: Map<String, List<Note>> =
        notes.flatMap { n -> n.tags.map { it.lowercase() to n } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()

    private val backlinks: Map<String, List<Backlink>>
    val unresolvedLinks: Int

    init {
        val back = HashMap<String, MutableList<Backlink>>()
        var unresolved = 0
        for (note in notes) {
            for (link in note.links) {
                if (link.target.isEmpty()) continue // [[#Заголовок]] — ссылка внутрь самой заметки
                val target = resolve(link.target, note.path)
                if (target == null) unresolved++ else back.getOrPut(target.path) { mutableListOf() } += Backlink(note, link)
            }
        }
        backlinks = back
        unresolvedLinks = unresolved
    }

    val size: Int get() = notes.size

    fun backlinksTo(path: String): List<Backlink> = backlinks[path].orEmpty()

    /**
     * Резолв как в Obsidian: путь (если в ссылке есть `/`), иначе имя файла, затем алиас.
     * Среди одноимённых побеждает заметка из папки [fromPath], иначе самая неглубокая.
     */
    fun resolve(target: String, fromPath: String? = null): Note? {
        val t = target.trim().replace('\\', '/').trimStart('/').removeSuffix(".md").lowercase()
        if (t.isEmpty()) return null
        if ('/' in t) {
            byPathNoExt[t]?.let { return it }
            return byPathNoExt.entries
                .filter { it.key.endsWith("/$t") }
                .minWithOrNull(compareBy({ it.value.depth }, { it.key }))
                ?.value
        }
        val candidates = byName[t] ?: byAlias[t] ?: return null
        if (candidates.size == 1) return candidates[0]
        val fromFolder = fromPath?.substringBeforeLast('/', "")
        candidates.firstOrNull { it.folder == fromFolder }?.let { return it }
        return candidates.minWith(compareBy({ it.depth }, { it.path }))
    }

    companion object {
        val EMPTY = VaultIndex(emptyList())
    }
}
