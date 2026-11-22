package dev.anykeyguru.vaultbridge.actions

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/** Сегодняшняя daily note по настройкам Obsidian; создаётся из шаблона, если её нет. */
class OpenDailyNoteAction : VaultAction() {
    override fun perform(project: Project, root: Path) {
        val now = LocalDateTime.now()
        NoteFiles.runAndOpen(project, "Opening daily note") {
            val config = DailyNoteConfig.parse(readIfExists(root.resolve(".obsidian/daily-notes.json")))
            val rel = config.relativePath(now)
            NoteFiles.createIfMissing(root.resolve(rel)) { content(root, config, NoteNames.title(rel), now) }
        }
    }

    private fun content(root: Path, config: DailyNoteConfig, title: String, now: LocalDateTime): String {
        val tplName = config.template.trim().trimStart('/').takeIf { it.isNotEmpty() } ?: return ""
        val tpl = readIfExists(root.resolve(if (tplName.endsWith(".md")) tplName else "$tplName.md")) ?: return ""
        val templatesJson = readIfExists(root.resolve(".obsidian/templates.json"))
        val dateFormat = templatesJson?.let { SimpleJson.string(it, "dateFormat") }?.takeIf { it.isNotBlank() }
        val timeFormat = templatesJson?.let { SimpleJson.string(it, "timeFormat") }?.takeIf { it.isNotBlank() }
        return Templates.apply(tpl, title, now, dateFormat ?: "YYYY-MM-DD", timeFormat ?: "HH:mm")
    }

    private fun readIfExists(file: Path): String? = if (Files.isRegularFile(file)) Files.readString(file) else null
}
