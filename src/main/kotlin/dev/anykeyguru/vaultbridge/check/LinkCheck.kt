package dev.anykeyguru.vaultbridge.check

import dev.anykeyguru.vaultbridge.editor.WikiLinkText
import dev.anykeyguru.vaultbridge.index.VaultIndex
import dev.anykeyguru.vaultbridge.model.Note
import dev.anykeyguru.vaultbridge.model.WikiLink

/** Итог проверки одной ссылки. */
enum class LinkVerdict {
    OK,
    MISSING_NOTE,
    MISSING_HEADING,

    /** Вложение (`[[diagram.png]]`) или `[[#Heading]]` вне заметки — не проверяем и не считаем. */
    SKIPPED,
}

enum class LinkProblem(val group: String) {
    MISSING_NOTE("Missing notes"),
    MISSING_HEADING("Missing headings"),
}

/** Битая ссылка в файле проекта. [line] — 0-based, [offset] — смещение `[[` в тексте файла. */
data class BrokenLink(
    val filePath: String,
    val fileLabel: String,
    val line: Int,
    val offset: Int,
    val link: String,
    val problem: LinkProblem,
    val reason: String,
)

/**
 * Результат прогона. [vaultMissing] — папка vault не найдена, ссылки посчитаны, но не проверены
 * ([broken] тогда пуст).
 */
data class LinkCheckResult(
    val checked: Int,
    val files: Int,
    val broken: List<BrokenLink>,
    val vaultMissing: Boolean = false,
    val vaultPath: String? = null,
)

/** Чистая логика проверки и форматирования — без платформы, чтобы тестировать обычным JUnit. */
object LinkCheck {
    /**
     * [noteText] возвращает текст заметки или null, если его не прочитать (тогда заголовок считаем существующим:
     * лучше пропустить, чем ложно ругаться).
     */
    fun classify(link: WikiLink, index: VaultIndex, noteText: (Note) -> String?): LinkVerdict {
        val target = link.target.trim()
        if (target.isEmpty() || WikiLinkText.isAttachment(target)) return LinkVerdict.SKIPPED
        val note = index.resolve(target) ?: return LinkVerdict.MISSING_NOTE
        val heading = link.heading ?: return LinkVerdict.OK
        val text = noteText(note) ?: return LinkVerdict.OK
        return if (headingExists(text, heading)) LinkVerdict.OK else LinkVerdict.MISSING_HEADING
    }

    /**
     * Есть ли заголовок в тексте заметки — та же нормализация, что при переходе по ссылке
     * ([WikiLinkText.headingOffset]). `[[Note#A#B]]` (вложенный путь Obsidian) проверяем по последнему сегменту,
     * `[[Note#^block]]` (ссылки на блоки) не проверяем.
     */
    fun headingExists(noteText: String, heading: String): Boolean {
        val last = heading.split('#').lastOrNull { it.isNotBlank() }?.trim() ?: return true
        if (last.startsWith('^')) return true
        return WikiLinkText.headingOffset(noteText, last) != null
    }

    fun reason(verdict: LinkVerdict, link: WikiLink): String = when (verdict) {
        LinkVerdict.MISSING_NOTE -> "note not found"
        LinkVerdict.MISSING_HEADING -> "heading “${link.heading?.trim()}” not found in ${link.target.trim()}"
        else -> ""
    }

    fun linkText(link: WikiLink): String = buildString {
        if (link.embed) append('!')
        append("[[").append(link.target.trim())
        link.heading?.let { append('#').append(it) }
        append("]]")
    }

    /** 0-based номер строки для смещения. */
    fun lineOf(text: CharSequence, offset: Int): Int {
        var n = 0
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') n++
        return n
    }

    fun summary(r: LinkCheckResult): String {
        val links = plural(r.checked, "link")
        val files = plural(r.files, "file")
        if (r.vaultMissing) return "$links in $files could not be checked: vault folder not found"
        return "$links checked in $files, ${r.broken.size} broken"
    }

    fun okMessage(r: LinkCheckResult): String =
        if (r.checked == 0) "No note links found in ${plural(r.files, "project file")}"
        else if (r.checked == 1) "The only note link is OK"
        else "All ${r.checked} note links are OK"

    /** Непустые группы в фиксированном порядке, строки внутри — по файлу и строке. */
    fun groups(r: LinkCheckResult): List<Pair<LinkProblem, List<BrokenLink>>> =
        LinkProblem.entries.mapNotNull { p ->
            r.broken.filter { it.problem == p }
                .sortedWith(compareBy({ it.fileLabel }, { it.offset }))
                .takeIf { it.isNotEmpty() }
                ?.let { p to it }
        }

    fun row(b: BrokenLink): String = "${b.fileLabel}:${b.line + 1}  ${b.link}  — ${b.reason}"

    fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"
}
