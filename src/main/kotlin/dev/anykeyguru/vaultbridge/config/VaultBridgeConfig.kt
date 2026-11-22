package dev.anykeyguru.vaultbridge.config

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/** Содержимое `.vaultbridge.json` в корне проекта. */
data class VaultBridgeConfig(
    /** Путь к vault как написан в файле (relative / absolute / `~/...`), null или пусто — не задан. */
    val vault: String?,
    /** null — блока `git` нет, никакой git-активности. */
    val git: GitSyncConfig?,
)

data class GitSyncConfig(
    val autoCommit: Boolean = true,
    val commitInterval: Duration = DEFAULT_COMMIT_INTERVAL,
    val push: Boolean = false,
    val pushInterval: Duration = DEFAULT_PUSH_INTERVAL,
    /** Только для «Clone vault», когда папки нет. Remotes мы никогда не добавляем. */
    val remote: String? = null,
) {
    companion object {
        val DEFAULT_COMMIT_INTERVAL: Duration = Duration.ofMinutes(5)
        val DEFAULT_PUSH_INTERVAL: Duration = Duration.ofMinutes(30)
        val MIN_COMMIT_INTERVAL: Duration = Duration.ofSeconds(30)
        val MIN_PUSH_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

/** Результат чтения файла конфигурации. */
sealed interface ConfigLoad {
    data object Missing : ConfigLoad

    data class Loaded(val file: Path, val config: VaultBridgeConfig, val vaultRoot: Path?) : ConfigLoad

    data class Invalid(val file: Path, val message: String) : ConfigLoad
}

class ConfigException(message: String) : Exception(message)

object ConfigParser {
    const val FILE_NAME = ".vaultbridge.json"

    fun load(projectRoot: Path, home: Path = userHome()): ConfigLoad {
        val file = projectRoot.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return ConfigLoad.Missing
        return try {
            val config = parse(Files.readString(file))
            val root = config.vault?.takeIf { it.isNotBlank() }?.let { resolveVaultPath(projectRoot, it, home) }
            ConfigLoad.Loaded(file, config, root)
        } catch (e: ConfigException) {
            ConfigLoad.Invalid(file, e.message ?: "Invalid config")
        } catch (e: Exception) {
            ConfigLoad.Invalid(file, e.message ?: e.toString())
        }
    }

    fun parse(text: String): VaultBridgeConfig {
        val cleaned = stripJsonc(text)
        if (cleaned.isBlank()) throw ConfigException("File is empty")
        val element = try {
            JsonParser.parseString(cleaned)
        } catch (e: Exception) {
            throw ConfigException(cleanGsonMessage(e))
        }
        if (!element.isJsonObject) throw ConfigException("Expected a JSON object { ... } at the top level")
        val obj = element.asJsonObject
        val vault = obj.optString("vault")
        val git = obj.get("git")?.let { g ->
            if (g.isJsonNull) return@let null
            if (!g.isJsonObject) throw ConfigException("\"git\" must be an object { ... }")
            parseGit(g.asJsonObject)
        }
        return VaultBridgeConfig(vault, git)
    }

    private fun parseGit(o: JsonObject): GitSyncConfig = GitSyncConfig(
        autoCommit = o.optBoolean("git.autoCommit", "autoCommit") ?: true,
        commitInterval = o.optString("commitInterval", "git.commitInterval")
            ?.let { parseDurationOrThrow("git.commitInterval", it, GitSyncConfig.MIN_COMMIT_INTERVAL) }
            ?: GitSyncConfig.DEFAULT_COMMIT_INTERVAL,
        push = o.optBoolean("git.push", "push") ?: false,
        pushInterval = o.optString("pushInterval", "git.pushInterval")
            ?.let { parseDurationOrThrow("git.pushInterval", it, GitSyncConfig.MIN_PUSH_INTERVAL) }
            ?: GitSyncConfig.DEFAULT_PUSH_INTERVAL,
        remote = o.optString("remote", "git.remote")?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun parseDurationOrThrow(name: String, raw: String, min: Duration): Duration {
        val d = Durations.parse(raw)
            ?: throw ConfigException("$name: cannot parse \"$raw\" (use 30s, 5m, 1h or 1h30m)")
        return if (d < min) min else d
    }

    private fun JsonObject.optString(key: String, display: String = key): String? {
        val e: JsonElement = get(key) ?: return null
        if (e.isJsonNull) return null
        if (e is JsonPrimitive && e.isString) return e.asString
        throw ConfigException("\"$display\" must be a string")
    }

    private fun JsonObject.optBoolean(display: String, key: String): Boolean? {
        val e: JsonElement = get(key) ?: return null
        if (e.isJsonNull) return null
        if (e is JsonPrimitive && e.isBoolean) return e.asBoolean
        throw ConfigException("\"$display\" must be true or false")
    }

    private fun cleanGsonMessage(e: Exception): String {
        var msg = (e.cause?.message ?: e.message ?: e.toString())
        msg = msg.substringBefore("\nSee https://")
        msg = msg.removePrefix("com.google.gson.stream.MalformedJsonException: ")
        msg = msg.removePrefix("java.io.EOFException: ")
        return msg.trim()
    }

    /**
     * Убирает `//` и `/* */` комментарии и висячие запятые перед `}` / `]`, не трогая строки.
     * Вырезанное заменяется пробелами, поэтому номера строк и колонок в ошибках Gson совпадают с файлом.
     */
    fun stripJsonc(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        var inString = false
        while (i < sb.length) {
            val c = sb[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
                i++
                continue
            }
            when {
                c == '"' -> inString = true
                c == '/' && i + 1 < sb.length && sb[i + 1] == '/' -> {
                    while (i < sb.length && sb[i] != '\n') {
                        if (sb[i] != '\r') sb.setCharAt(i, ' ')
                        i++
                    }
                    continue
                }
                c == '/' && i + 1 < sb.length && sb[i + 1] == '*' -> {
                    val end = sb.indexOf("*/", i + 2).let { if (it < 0) sb.length else it + 2 }
                    for (j in i until end) if (sb[j] != '\n' && sb[j] != '\r') sb.setCharAt(j, ' ')
                    i = end
                    continue
                }
            }
            i++
        }
        // второй проход: висячие запятые (комментариев уже нет)
        inString = false
        i = 0
        while (i < sb.length) {
            val c = sb[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else if (c == '"') {
                inString = true
            } else if (c == ',') {
                var j = i + 1
                while (j < sb.length && sb[j].isWhitespace()) j++
                if (j < sb.length && (sb[j] == '}' || sb[j] == ']')) sb.setCharAt(i, ' ')
            }
            i++
        }
        return sb.toString()
    }

    /** `~` / `~/x` — от домашней папки, абсолютный — как есть, остальное — от корня проекта. */
    fun resolveVaultPath(projectRoot: Path, raw: String, home: Path = userHome()): Path {
        val s = raw.trim()
        val p = when {
            s == "~" -> home
            s.startsWith("~/") || s.startsWith("~\\") -> home.resolve(s.substring(2))
            Paths.get(s).isAbsolute -> Paths.get(s)
            else -> projectRoot.resolve(s)
        }
        return p.toAbsolutePath().normalize()
    }

    /**
     * Как записать путь к vault в новый `.vaultbridge.json`: относительно проекта, если это недалеко
     * (не больше трёх `..`), иначе `~/...`, иначе абсолютный.
     */
    fun suggestVaultValue(projectRoot: Path, vault: Path, home: Path = userHome()): String {
        val v = vault.toAbsolutePath().normalize()
        val p = projectRoot.toAbsolutePath().normalize()
        runCatching {
            if (v.root == p.root) {
                val rel = p.relativize(v)
                val ups = rel.count { it.toString() == ".." }
                if (ups <= 3) return rel.joinToString("/").ifEmpty { "." }
            }
        }
        val h = home.toAbsolutePath().normalize()
        if (v.startsWith(h) && v != h) return "~/" + h.relativize(v).joinToString("/")
        return v.toString()
    }

    private fun userHome(): Path = Paths.get(System.getProperty("user.home"))
}

/** Длительности вида `30s`, `5m`, `1h`, `1h30m`, `90` (секунды). */
object Durations {
    private val PART = Regex("(\\d+)\\s*([hms])")

    fun parse(raw: String): Duration? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return Duration.ofSeconds(it) }
        var total = Duration.ZERO
        var pos = 0
        var lastUnit = -1
        while (pos < s.length) {
            if (s[pos].isWhitespace()) { pos++; continue }
            val m = PART.matchAt(s, pos) ?: return null
            val n = m.groupValues[1].toLong()
            val unitIdx = "hms".indexOf(m.groupValues[2][0])
            if (unitIdx <= lastUnit) return null // 5m1h, 1h1h
            lastUnit = unitIdx
            total = total.plus(
                when (unitIdx) {
                    0 -> Duration.ofHours(n)
                    1 -> Duration.ofMinutes(n)
                    else -> Duration.ofSeconds(n)
                },
            )
            pos = m.range.last + 1
        }
        return total
    }

    fun format(d: Duration): String {
        val h = d.toHours()
        val m = d.toMinutesPart()
        val s = d.toSecondsPart()
        return buildString {
            if (h > 0) append(h).append('h')
            if (m > 0) append(m).append('m')
            if (s > 0 || isEmpty()) append(s).append('s')
        }
    }
}
