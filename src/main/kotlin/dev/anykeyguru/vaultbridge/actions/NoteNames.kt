package dev.anykeyguru.vaultbridge.actions

/** Имя новой заметки из пользовательского ввода → относительный путь `folder/Name.md`. */
object NoteNames {
    /** Символы, которые Obsidian не допускает в именах файлов / ломают `[[ссылки]]`. */
    private val FORBIDDEN = Regex("""[*"<>:|?#^\[\]\\\u0000-\u001F]""")

    fun toRelativePath(input: String): String? {
        val segments = input.trim().replace('\\', '/').removeSuffixIgnoreCase(".md")
            .split('/')
            .map { it.replace(FORBIDDEN, "-").trim().trimStart('.').trim() }
            .filter { it.isNotEmpty() && it != ".." }
        if (segments.isEmpty()) return null
        return segments.joinToString("/") + ".md"
    }

    /** Имя заметки (без папки и `.md`) — для заголовка `# Name`. */
    fun title(relPath: String): String = relPath.substringAfterLast('/').removeSuffix(".md")

    private fun String.removeSuffixIgnoreCase(suffix: String) =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
