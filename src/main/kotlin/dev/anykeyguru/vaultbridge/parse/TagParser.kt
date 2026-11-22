package dev.anykeyguru.vaultbridge.parse

object TagParser {
    // `#tag` в начале строки или после пробела/скобки; `# Заголовок` (с пробелом) не тег.
    private val TAG = Regex("""(?:^|(?<=[\s(\[{,;]))#([\p{L}\p{N}_/\-]+)""", RegexOption.MULTILINE)

    /** Инлайн-теги без `#`. Текст должен быть уже без кода (см. [MarkdownMask]). */
    fun parse(text: CharSequence): List<String> =
        TAG.findAll(text)
            .map { it.groupValues[1].trim('/') }
            // как в Obsidian: тег должен содержать хотя бы один нецифровой символ
            .filter { tag -> tag.isNotEmpty() && tag.any { !it.isDigit() } }
            .distinct()
            .toList()
}
