package dev.anykeyguru.vaultbridge.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** IDE открывает .vaultbridge.json как JSON5 — парсер должен принимать то, что JSON5 разрешает. */
class Json5CompatTest {
    @Test
    fun acceptsJson5Syntax() {
        val cfg = ConfigParser.parse(
            """
            {
              // comment
              vault: '../notes',          /* unquoted key, single quotes */
              git: { push: true, pushInterval: "10m", },
            }
            """.trimIndent()
        )
        assertEquals("../notes", cfg.vault)
        assertTrue(cfg.git!!.push)
    }
}
