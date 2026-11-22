package dev.anykeyguru.vaultbridge.model

/**
 * `[[target#heading|alias]]` или `![[embed]]`.
 * [start]/[end] — смещения всей конструкции (включая скобки) в исходном тексте.
 */
data class WikiLink(
    val target: String,
    val heading: String?,
    val alias: String?,
    val embed: Boolean,
    val start: Int,
    val end: Int,
)
