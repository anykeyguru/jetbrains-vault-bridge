package dev.anykeyguru.vaultbridge.parse

import dev.anykeyguru.vaultbridge.model.Note

object NoteParser {
    fun parse(path: String, text: String): Note {
        val fm = FrontmatterParser.parse(text)
        val masked = MarkdownMask.maskCode(text)
        val body = masked.substring(fm.bodyStart)
        val links = WikiLinkParser.parse(masked).filter { it.start >= fm.bodyStart }
        val tags = (fm.tags + TagParser.parse(body)).distinctBy { it.lowercase() }
        val name = path.substringAfterLast('/').removeSuffix(".md")
        return Note(
            path = path,
            title = fm.title ?: name,
            aliases = fm.aliases,
            tags = tags,
            links = links,
        )
    }
}
