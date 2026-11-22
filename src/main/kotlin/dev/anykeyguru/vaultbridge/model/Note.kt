package dev.anykeyguru.vaultbridge.model

/**
 * Заметка vault. [path] — относительный путь с `/` и расширением `.md`.
 */
data class Note(
    val path: String,
    val title: String,
    val aliases: List<String>,
    val tags: List<String>,
    val links: List<WikiLink>,
) {
    /** Имя файла без `.md` — по нему Obsidian резолвит `[[ссылки]]`. */
    val name: String = path.substringAfterLast('/').removeSuffix(".md")

    /** Папка относительно корня vault, `""` для корня. */
    val folder: String = path.substringBeforeLast('/', "")

    val depth: Int = path.count { it == '/' }
}
