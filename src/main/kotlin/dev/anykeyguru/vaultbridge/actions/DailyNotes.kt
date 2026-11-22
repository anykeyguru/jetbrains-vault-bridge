package dev.anykeyguru.vaultbridge.actions

import java.time.LocalDateTime

/** Настройки core-плагина Daily notes из `.obsidian/daily-notes.json`. */
data class DailyNoteConfig(val folder: String = "", val format: String = DEFAULT_FORMAT, val template: String = "") {
    /** Относительный путь заметки за [now]; формат может содержать `/` (подпапки по датам). */
    fun relativePath(now: LocalDateTime): String {
        val name = MomentFormat.format(format, now).trim().trim('/')
        val dir = folder.trim().replace('\\', '/').trim('/')
        return (if (dir.isEmpty()) name else "$dir/$name") + ".md"
    }

    companion object {
        const val DEFAULT_FORMAT = "YYYY-MM-DD"

        fun parse(json: String?): DailyNoteConfig {
            if (json == null) return DailyNoteConfig()
            return DailyNoteConfig(
                folder = SimpleJson.string(json, "folder").orEmpty(),
                format = SimpleJson.string(json, "format")?.takeIf { it.isNotBlank() } ?: DEFAULT_FORMAT,
                template = SimpleJson.string(json, "template").orEmpty(),
            )
        }
    }
}

/** Подстановки core-плагина Templates: `{{title}}`, `{{date}}`, `{{time}}`, `{{date:FORMAT}}`, `{{time:FORMAT}}`. */
object Templates {
    private val VAR = Regex("""\{\{\s*(date|time|title)\s*(?::([^}]*))?}}""", RegexOption.IGNORE_CASE)

    fun apply(
        template: String,
        title: String,
        now: LocalDateTime,
        dateFormat: String = "YYYY-MM-DD",
        timeFormat: String = "HH:mm",
    ): String = VAR.replace(template) { m ->
        val custom = m.groupValues[2].trim().takeIf { it.isNotEmpty() }
        when (m.groupValues[1].lowercase()) {
            "title" -> title
            "date" -> MomentFormat.format(custom ?: dateFormat, now)
            else -> MomentFormat.format(custom ?: timeFormat, now)
        }
    }
}

/** Достаём строковые поля верхнего уровня из маленьких конфигов Obsidian без JSON-библиотеки. */
internal object SimpleJson {
    fun string(json: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return unescape(m.groupValues[1])
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i >= s.length) {
                sb.append(c)
                continue
            }
            when (val e = s[i++]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> if (i + 4 <= s.length) {
                    sb.append(s.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }
                else -> sb.append(e)
            }
        }
        return sb.toString()
    }
}
