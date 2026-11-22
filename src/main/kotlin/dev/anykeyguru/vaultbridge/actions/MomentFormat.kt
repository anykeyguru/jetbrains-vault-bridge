package dev.anykeyguru.vaultbridge.actions

import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAccessor
import java.time.temporal.WeekFields
import java.util.Locale

private typealias Fmt = (TemporalAccessor, Locale) -> String

/**
 * Форматирование дат в синтаксисе moment.js (как в настройках Obsidian) через java.time.
 * Поддерживаются частые токены и `[литералы]`; прочие символы выводятся как есть.
 */
object MomentFormat {
    private fun pattern(p: String): Fmt = { t, l -> DateTimeFormatter.ofPattern(p, l).format(t) }
    private fun num(pad: Int, get: (TemporalAccessor, Locale) -> Int): Fmt =
        { t, l -> get(t, l).toString().padStart(pad, '0') }

    private val TOKENS: List<Pair<String, Fmt>> = listOf(
        "YYYY" to pattern("uuuu"),
        "YY" to pattern("uu"),
        "MMMM" to pattern("MMMM"),
        "MMM" to pattern("MMM"),
        "MM" to pattern("MM"),
        "M" to pattern("M"),
        "DDDD" to num(3) { t, _ -> t.get(ChronoField.DAY_OF_YEAR) },
        "DDD" to num(1) { t, _ -> t.get(ChronoField.DAY_OF_YEAR) },
        "Do" to { t, _ -> ordinal(t.get(ChronoField.DAY_OF_MONTH)) },
        "DD" to pattern("dd"),
        "D" to pattern("d"),
        "dddd" to { t, l -> DayOfWeek.from(t).getDisplayName(TextStyle.FULL, l) },
        "ddd" to { t, l -> DayOfWeek.from(t).getDisplayName(TextStyle.SHORT, l) },
        "dd" to { t, l -> DayOfWeek.from(t).getDisplayName(TextStyle.SHORT, l).take(2) },
        "d" to num(1) { t, _ -> t.get(ChronoField.DAY_OF_WEEK) % 7 }, // moment: 0 = воскресенье
        "E" to num(1) { t, _ -> t.get(ChronoField.DAY_OF_WEEK) },
        "GGGG" to num(4) { t, _ -> t.get(IsoFields.WEEK_BASED_YEAR) },
        "gggg" to num(4) { t, l -> t.get(WeekFields.of(l).weekBasedYear()) },
        "WW" to num(2) { t, _ -> t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) },
        "W" to num(1) { t, _ -> t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) },
        "ww" to num(2) { t, l -> t.get(WeekFields.of(l).weekOfWeekBasedYear()) },
        "w" to num(1) { t, l -> t.get(WeekFields.of(l).weekOfWeekBasedYear()) },
        "Q" to num(1) { t, _ -> t.get(IsoFields.QUARTER_OF_YEAR) },
        "HH" to pattern("HH"),
        "H" to pattern("H"),
        "hh" to pattern("hh"),
        "h" to pattern("h"),
        "mm" to pattern("mm"),
        "m" to pattern("m"),
        "ss" to pattern("ss"),
        "s" to pattern("s"),
        "A" to { t, _ -> if (t.get(ChronoField.AMPM_OF_DAY) == 0) "AM" else "PM" },
        "a" to { t, _ -> if (t.get(ChronoField.AMPM_OF_DAY) == 0) "am" else "pm" },
    ).sortedByDescending { it.first.length } // жадно: YYYY раньше YY

    fun format(moment: String, time: TemporalAccessor, locale: Locale = Locale.ENGLISH): String {
        val out = StringBuilder()
        var i = 0
        while (i < moment.length) {
            if (moment[i] == '[') {
                val end = moment.indexOf(']', i + 1)
                if (end > 0) {
                    out.append(moment, i + 1, end)
                    i = end + 1
                    continue
                }
            }
            val token = TOKENS.firstOrNull { moment.startsWith(it.first, i) }
            if (token != null) {
                out.append(token.second(time, locale))
                i += token.first.length
            } else {
                out.append(moment[i++])
            }
        }
        return out.toString()
    }

    private fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
