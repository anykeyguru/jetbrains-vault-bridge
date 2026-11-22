package dev.anykeyguru.vaultbridge.actions

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class MomentFormatTest {
    private val t = LocalDateTime.of(2026, 3, 7, 14, 5, 9) // суббота

    @Test
    fun commonTokens() {
        assertEquals("2026-03-07", MomentFormat.format("YYYY-MM-DD", t))
        assertEquals("26/3/7", MomentFormat.format("YY/M/D", t))
        assertEquals("Mar March", MomentFormat.format("MMM MMMM", t))
        assertEquals("Sat Saturday", MomentFormat.format("ddd dddd", t))
        assertEquals("14:05:09 PM", MomentFormat.format("HH:mm:ss A", t))
        assertEquals("March 7th, 2026", MomentFormat.format("MMMM Do, YYYY", t))
    }

    @Test
    fun literalsAndFolders() {
        assertEquals("2026/03/Week 10 2026-03-07", MomentFormat.format("YYYY/MM/[Week] WW YYYY-MM-DD", t))
        assertEquals("Daily YYYY 2026", MomentFormat.format("[Daily YYYY] YYYY", t))
        assertEquals("2026-W10", MomentFormat.format("GGGG-[W]WW", t))
    }

    @Test
    fun ordinals() {
        val jan = LocalDateTime.of(2026, 1, 1, 0, 0)
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 31).map { MomentFormat.format("Do", jan.withDayOfMonth(it)) },
        )
    }

    @Test
    fun dailyConfig() {
        val c = DailyNoteConfig.parse("""{"folder":"Journal/","format":"YYYY/MM/YYYY-MM-DD","template":"Templates/Daily"}""")
        assertEquals("Journal/2026/03/2026-03-07.md", c.relativePath(t))
        assertEquals("Templates/Daily", c.template)
        assertEquals("2026-03-07.md", DailyNoteConfig.parse(null).relativePath(t))
        assertEquals("2026-03-07.md", DailyNoteConfig.parse("""{"format": ""}""").relativePath(t))
        assertEquals("a\"b", SimpleJson.string("""{"x": "a\"b"}""", "x"))
    }

    @Test
    fun templateVariables() {
        val tpl = "# {{title}}\nCreated {{date}} {{time}}\n{{date:dddd, MMMM D}} {{ TIME:HH }}"
        assertEquals("# 2026-03-07\nCreated 2026-03-07 14:05\nSaturday, March 7 14",
            Templates.apply(tpl, "2026-03-07", t))
    }
}
