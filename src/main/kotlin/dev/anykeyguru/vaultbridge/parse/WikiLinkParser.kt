package dev.anykeyguru.vaultbridge.parse

import dev.anykeyguru.vaultbridge.model.WikiLink

object WikiLinkParser {
    private val LINK = Regex("""(!?)\[\[([^\[\]\n]+?)]]""")

    /** Все `[[ссылки]]` в тексте, без учёта контекста (подходит для комментариев в коде). */
    fun parse(text: CharSequence): List<WikiLink> =
        LINK.findAll(text).mapNotNull { m ->
            val inner = m.groupValues[2]
            // В таблицах Obsidian экранирует разделитель: [[note\|alias]]
            val pipe = inner.indexOf('|')
            val targetPart = (if (pipe >= 0) inner.substring(0, pipe) else inner).removeSuffix("\\")
            val alias = if (pipe >= 0) inner.substring(pipe + 1).trim().ifEmpty { null } else null
            val hash = targetPart.indexOf('#')
            val target = (if (hash >= 0) targetPart.substring(0, hash) else targetPart).trim()
            val heading = if (hash >= 0) targetPart.substring(hash + 1).trim().ifEmpty { null } else null
            if (target.isEmpty() && heading == null) return@mapNotNull null
            WikiLink(target, heading, alias, m.groupValues[1] == "!", m.range.first, m.range.last + 1)
        }.toList()
}
