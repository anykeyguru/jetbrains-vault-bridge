package dev.anykeyguru.vaultbridge.editor

import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.Lookup

/**
 * Пока открыт список внутри `[[…`: пробел, `/`, `-`, `.` — часть имени заметки, а не «выбрать и закрыть».
 * `#` и `|` закрывают список, не вставляя вариант: дальше заголовок (откроется свой список) или алиас.
 */
class WikiLinkCharFilter : CharFilter() {
    override fun acceptChar(c: Char, prefixLength: Int, lookup: Lookup): Result? {
        val editor = lookup.editor
        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset
        val inName = WikiLinkText.completionPrefixStart(text, offset) != null
        val inHeading = !inName && WikiLinkText.headingQuery(text, offset) != null
        if (!inName && !inHeading) return null
        return when (c) {
            ' ', '/', '-', '.', '_', '(', ')', ',', '\'' -> Result.ADD_TO_PREFIX
            '#' -> if (inName) Result.HIDE_LOOKUP else Result.ADD_TO_PREFIX
            '|' -> Result.HIDE_LOOKUP
            else -> null
        }
    }
}
