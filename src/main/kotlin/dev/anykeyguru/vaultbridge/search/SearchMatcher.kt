package dev.anykeyguru.vaultbridge.search

import dev.anykeyguru.vaultbridge.model.Note

/** Текст заметки и его lowercase-копия (чтобы не понижать регистр на каждый символ ввода). */
class NoteContent(val text: String) {
    val lower: String = text.lowercase()
}

data class SearchHit(val note: Note, val score: Int, val snippet: String? = null)

/**
 * Чистая логика поиска: все слова запроса должны найтись хотя бы в одном поле.
 * Вес слова — вес лучшего поля: заголовок/имя > алиас > тег > путь > содержимое.
 */
object SearchMatcher {
    const val TITLE = 50
    const val ALIAS = 40
    const val TAG = 30
    const val PATH = 20
    const val CONTENT = 10
    private const val EXACT_BONUS = 100
    private const val PREFIX_BONUS = 5

    private val WS = Regex("\\s+")

    fun words(query: String): List<String> = query.trim().lowercase().split(WS).filter { it.isNotEmpty() }

    fun metaWeight(note: Note, word: String): Int {
        val tagWord = word.removePrefix("#")
        return when {
            note.title.lowercase().contains(word) || note.name.lowercase().contains(word) -> TITLE
            note.aliases.any { it.lowercase().contains(word) } -> ALIAS
            tagWord.isNotEmpty() && note.tags.any { it.lowercase().contains(tagWord) } -> TAG
            note.path.lowercase().contains(word) -> PATH
            else -> 0
        }
    }

    /**
     * Совпадение заметки с запросом. [content] == null — текст ещё не прочитан:
     * тогда заметка проходит, только если все слова нашлись в метаданных.
     */
    fun match(note: Note, words: List<String>, content: NoteContent?): SearchHit? {
        if (words.isEmpty()) return SearchHit(note, 0)
        var score = 0
        var snippetWord: String? = null
        for (w in words) {
            val meta = metaWeight(note, w)
            score += when {
                meta > 0 -> meta
                content != null && content.lower.contains(w) -> {
                    if (snippetWord == null) snippetWord = w
                    CONTENT
                }
                else -> return null
            }
        }
        val title = note.title.lowercase()
        val query = words.joinToString(" ")
        if (title == query || note.name.lowercase() == query) score += EXACT_BONUS
        else if (title.startsWith(words[0]) || note.name.lowercase().startsWith(words[0])) score += PREFIX_BONUS
        return SearchHit(note, score, snippetWord?.let { snippet(content!!, it) })
    }

    val ORDER: Comparator<SearchHit> =
        compareByDescending<SearchHit> { it.score }.thenBy { it.note.title.lowercase() }.thenBy { it.note.path }

    /** Фрагмент вокруг первого вхождения [word], в одну строку. */
    fun snippet(content: NoteContent, word: String, before: Int = 30, after: Int = 70): String {
        // lowercase может поменять длину (İ → i̇) — тогда показываем lowercase-текст, зато без сдвига
        val src = if (content.lower.length == content.text.length) content.text else content.lower
        val idx = content.lower.indexOf(word).coerceAtLeast(0)
        val start = (idx - before).coerceAtLeast(0)
        val end = (idx + word.length + after).coerceAtMost(src.length)
        val body = src.substring(start, end).replace(WS, " ").trim()
        return (if (start > 0) "…" else "") + body + (if (end < src.length) "…" else "")
    }
}
