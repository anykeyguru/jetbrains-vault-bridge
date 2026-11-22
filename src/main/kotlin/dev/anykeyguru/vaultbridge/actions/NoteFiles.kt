package dev.anykeyguru.vaultbridge.actions

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Открытие/создание заметок: весь IO в фоне, редактор — на EDT. Vault может лежать вне проекта. */
object NoteFiles {
    fun open(project: Project, file: Path) = runAndOpen(project, "Opening note") { file }

    /**
     * Фоновая задача: [resolve] вычисляет путь (и при необходимости создаёт файл через java.nio),
     * затем VFS refresh и открытие в редакторе.
     */
    fun runAndOpen(project: Project, title: String, resolve: () -> Path) {
        object : Task.Backgroundable(project, title, false) {
            private var vFile: VirtualFile? = null
            private var path: Path? = null

            override fun run(indicator: ProgressIndicator) {
                val p = resolve().also { path = it }
                vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)
            }

            override fun onSuccess() {
                val f = vFile
                if (f == null) {
                    Messages.showErrorDialog(project, "Cannot open $path", "Vault Bridge")
                } else if (!project.isDisposed) {
                    FileEditorManager.getInstance(project).openFile(f, true)
                }
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, error.message ?: error.toString(), "Vault Bridge")
            }
        }.queue()
    }

    /** Создаёт файл с [content], если его ещё нет. Возвращает [file]. */
    fun createIfMissing(file: Path, content: () -> String): Path {
        if (!Files.exists(file)) {
            file.parent?.let { Files.createDirectories(it) }
            try {
                Files.writeString(file, content(), StandardOpenOption.CREATE_NEW)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // создали параллельно (например, Obsidian) — просто откроем
            }
        }
        return file
    }
}
