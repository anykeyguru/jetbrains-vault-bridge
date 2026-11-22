package dev.anykeyguru.vaultbridge

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object VaultBridgeIcons {
    /** 16×16; тёмный вариант `vault_dark.svg` IDE подхватывает сама. */
    @JvmField
    val Vault: Icon = IconLoader.getIcon("/icons/vault.svg", VaultBridgeIcons::class.java)
}
