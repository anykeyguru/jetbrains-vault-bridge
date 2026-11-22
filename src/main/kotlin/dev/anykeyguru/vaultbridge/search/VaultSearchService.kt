package dev.anykeyguru.vaultbridge.search

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.anykeyguru.vaultbridge.index.VaultIndexListener
import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Кэш содержимого заметок для полнотекстового поиска: ключ — относительный путь, проверка по mtime.
 * Индекс текст не хранит, поэтому читаем с диска (только из фоновых потоков).
 */
@Service(Service.Level.PROJECT)
class VaultSearchService(project: Project, val scope: CoroutineScope) : Disposable {
    private class Entry(val mtime: Long, val content: NoteContent)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var root: String? = null

    /** Время последнего завершённого индексирования, которое видел сервис (0 — не видел). */
    @Volatile
    var lastIndexedAt: Long = 0
        private set

    init {
        project.messageBus.connect(this).subscribe(VaultIndexListener.TOPIC, VaultIndexListener { state ->
            if (state.root != root) {
                cache.clear()
                root = state.root
            } else if (!state.indexing) {
                cache.keys.retainAll(state.index.notes.keys)
            }
            if (!state.indexing && state.error == null && state.root != null) lastIndexedAt = System.currentTimeMillis()
        })
    }

    /** Текст заметки или null, если файл не читается / слишком большой. Вызывать не на EDT. */
    fun content(vaultRoot: Path, relPath: String): NoteContent? {
        val file = vaultRoot.resolve(relPath)
        return try {
            val mtime = Files.getLastModifiedTime(file).toMillis()
            cache[relPath]?.takeIf { it.mtime == mtime }?.let { return it.content }
            if (Files.size(file) > MAX_FILE_SIZE) return null
            val content = NoteContent(decode(Files.readAllBytes(file)))
            cache[relPath] = Entry(mtime, content)
            content
        } catch (_: Exception) {
            null
        }
    }

    override fun dispose() = cache.clear()

    companion object {
        private const val MAX_FILE_SIZE = 4L * 1024 * 1024

        fun getInstance(project: Project): VaultSearchService = project.service()

        internal fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}
