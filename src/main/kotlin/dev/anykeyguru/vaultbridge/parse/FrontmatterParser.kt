package dev.anykeyguru.vaultbridge.parse

data class Frontmatter(
    val title: String?,
    val aliases: List<String>,
    val tags: List<String>,
    /** Смещение, с которого начинается тело заметки. */
    val bodyStart: Int,
) {
    companion object {
        val NONE = Frontmatter(null, emptyList(), emptyList(), 0)
    }
}

/**
 * Минимальный разбор YAML-frontmatter: только `title`, `aliases`/`alias`, `tags`/`tag`.
 * Поддерживает скаляры, инлайн-списки `[a, b]` и блочные списки `- a`.
 */
object FrontmatterParser {
    fun parse(text: String): Frontmatter {
        val start = if (text.startsWith("﻿")) 1 else 0
        if (!text.startsWith("---", start)) return Frontmatter.NONE
        val firstNl = text.indexOf('\n', start)
        if (firstNl < 0 || text.substring(start, firstNl).trimEnd() != "---") return Frontmatter.NONE

        val lines = mutableListOf<String>()
        var pos = firstNl + 1
        var bodyStart = -1
        while (pos <= text.length) {
            val nl = text.indexOf('\n', pos)
            val end = if (nl < 0) text.length else nl
            val line = text.substring(pos, end).trimEnd('\r')
            if (line.trimEnd() == "---" || line.trimEnd() == "...") {
                bodyStart = if (nl < 0) text.length else nl + 1
                break
            }
            lines += line
            if (nl < 0) break
            pos = nl + 1
        }
        if (bodyStart < 0) return Frontmatter.NONE

        val values = mutableMapOf<String, MutableList<String>>()
        var currentKey: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("- ") || trimmed == "-") {
                val key = currentKey ?: continue
                unquote(trimmed.removePrefix("-").trim()).takeIf { it.isNotEmpty() }
                    ?.let { values.getOrPut(key) { mutableListOf() } += it }
                continue
            }
            if (line.first().isWhitespace()) continue // вложенные структуры не нужны
            val colon = line.indexOf(':')
            if (colon <= 0) {
                currentKey = null
                continue
            }
            val key = line.substring(0, colon).trim().lowercase()
            currentKey = key
            val raw = line.substring(colon + 1).trim()
            if (raw.isEmpty()) continue
            values.getOrPut(key) { mutableListOf() } += splitValue(key, raw)
        }

        val tags = (values["tags"].orEmpty() + values["tag"].orEmpty())
            .map { it.removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
        return Frontmatter(
            title = values["title"]?.firstOrNull(),
            aliases = (values["aliases"].orEmpty() + values["alias"].orEmpty()).distinct(),
            tags = tags.distinct(),
            bodyStart = bodyStart,
        )
    }

    private fun splitValue(key: String, raw: String): List<String> {
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return raw.substring(1, raw.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
        }
        val value = unquote(raw)
        // `tags: a b` и `tags: a, b` — оба варианта встречаются в живых vault
        if (key == "tags" || key == "tag") return value.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        return listOf(value)
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) {
            s.substring(1, s.length - 1)
        } else {
            s
        }
}
