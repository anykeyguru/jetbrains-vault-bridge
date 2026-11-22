package dev.anykeyguru.vaultbridge.editor

import dev.anykeyguru.vaultbridge.model.WikiLink
import dev.anykeyguru.vaultbridge.parse.MarkdownMask
import dev.anykeyguru.vaultbridge.parse.WikiLinkParser

/** Чистая логика поверх текста документа: без PSI и платформы, чтобы легко тестировать. */
object WikiLinkText {
    /** Дальше назад от каретки `[[` не ищем — ссылки короткие и однострочные. */
    private const val MAX_SCAN = 256

    private val ATTACHMENT_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "avif", "pdf", "mp3", "wav", "ogg", "m4a", "flac",
        "mp4", "webm", "mov", "mkv", "canvas", "base", "excalidraw",
    )
    private val HEADING = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val SPACES = Regex("\\s+")
    private val BAD_NAME_CHARS = setOf('*', '"', '<', '>', ':', '|', '?', '\\', '[', ']', '#', '^')

    /** Ссылка, внутри которой (включая скобки) стоит [offset]; смещения — в координатах [text]. */
    fun linkAt(text: CharSequence, offset: Int): WikiLink? {
        if (offset < 0 || offset > text.length) return null
        val ls = lineStart(text, offset)
        return WikiLinkParser.parse(text.subSequence(ls, lineEnd(text, offset)))
            .firstOrNull { ls + it.start <= offset && offset < ls + it.end }
            ?.let { it.copy(start = it.start + ls, end = it.end + ls) }
    }

    /**
     * Если каретка стоит в незакрытом `[[префикс`, возвращает смещение начала префикса (сразу после `[[`).
     * После `#` или `|` (заголовок / алиас) имена заметок не предлагаем.
     */
    fun completionPrefixStart(text: CharSequence, offset: Int): Int? {
        if (offset > text.length) return null
        var i = offset - 1
        val min = maxOf(0, offset - MAX_SCAN)
        while (i >= min) {
            when (text[i]) {
                '\n', ']', '|', '#' -> return null
                '[' -> return if (i > 0 && text[i - 1] == '[') i + 1 else null
            }
            i--
        }
        return null
    }

    /** Сколько `]` (0..2) уже стоит сразу с [offset] — чтобы дописать только недостающие. */
    fun closingBracketsAt(text: CharSequence, offset: Int): Int {
        var n = 0
        while (n < 2 && offset + n < text.length && text[offset + n] == ']') n++
        return n
    }

    /** Смещение начала строки заголовка `# heading` в тексте заметки (код-блоки пропускаются) или null. */
    fun headingOffset(noteText: String, heading: String): Int? {
        val want = normalizeHeading(heading)
        if (want.isEmpty()) return null
        var lineStart = 0
        for (line in MarkdownMask.maskCode(noteText).split('\n')) {
            val m = HEADING.find(line)
            if (m != null && normalizeHeading(m.groupValues[2]) == want) return lineStart
            lineStart += line.length + 1
        }
        return null
    }

    /** `[[diagram.png]]`, `[[doc.pdf]]` — вложения, а не заметки; их не подсвечиваем как битые. */
    fun isAttachment(target: String): Boolean {
        val ext = target.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext in ATTACHMENT_EXT
    }

    /** Можно ли создать `<vault>/<target>.md` из такой ссылки. */
    fun isValidNoteName(target: String): Boolean {
        val t = target.trim()
        if (t.isEmpty() || t.startsWith('/') || t.endsWith('/') || t.any { it in BAD_NAME_CHARS || it < ' ' }) return false
        return t.split('/').none { it.isBlank() || it == "." || it == ".." || it.startsWith('.') }
    }

    private fun normalizeHeading(s: String): String = s.trim().replace(SPACES, " ").lowercase()

    private fun lineStart(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }
}
