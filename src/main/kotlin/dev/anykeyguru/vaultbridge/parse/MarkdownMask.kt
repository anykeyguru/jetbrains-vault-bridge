package dev.anykeyguru.vaultbridge.parse

/**
 * Заменяет пробелами блоки кода (``` / ~~~) и инлайн-код, сохраняя длину текста,
 * чтобы смещения ссылок оставались верными. Obsidian не видит ссылки и теги внутри кода.
 */
object MarkdownMask {
    private val INLINE_CODE = Regex("(`+)[^`\n]*?\\1")

    fun maskCode(text: String): String {
        val out = StringBuilder(text.length)
        var fence: String? = null
        var lineStart = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            val line = text.substring(lineStart, lineEnd)
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            when {
                fence != null -> {
                    blank(out, line)
                    if (marker == fence) fence = null
                }
                marker != null -> {
                    blank(out, line)
                    fence = marker
                }
                else -> out.append(INLINE_CODE.replace(line) { " ".repeat(it.value.length) })
            }
            if (nl < 0) break
            out.append('\n')
            lineStart = nl + 1
        }
        return out.toString()
    }

    private fun blank(out: StringBuilder, line: String) {
        repeat(line.length) { out.append(' ') }
    }
}
