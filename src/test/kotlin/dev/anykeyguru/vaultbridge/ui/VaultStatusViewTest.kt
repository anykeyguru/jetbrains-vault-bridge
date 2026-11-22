package dev.anykeyguru.vaultbridge.ui

import dev.anykeyguru.vaultbridge.index.VaultIndex
import dev.anykeyguru.vaultbridge.index.VaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultStatusViewTest {
    @Test
    fun missingFolderIsRedWithPathAndHint() {
        val v = VaultStatusView.of(VaultState("/home/me/Vault", VaultIndex.EMPTY, indexing = false, error = "Folder not found"))
        assertEquals("Vault: missing", v.text)
        assertTrue(v.error)
        assertEquals(
            "/home/me/Vault\nFolder not found — check Settings > Tools > Vault Bridge or .vaultbridge.json",
            v.tooltip,
        )
    }

    @Test
    fun otherStatesUnchanged() {
        val notSet = VaultStatusView.of(VaultState(null, VaultIndex.EMPTY, indexing = false))
        assertEquals("Vault: not set", notSet.text)
        assertFalse(notSet.error)
        assertEquals("Vault: Vault · indexing…", VaultStatusView.of(VaultState("/v/Vault", VaultIndex.EMPTY, indexing = true)).text)
        val ok = VaultStatusView.of(VaultState("/v/Vault", VaultIndex.EMPTY, indexing = false))
        assertEquals("Vault: Vault · 0 notes", ok.text)
        assertFalse(ok.error)
    }
}
