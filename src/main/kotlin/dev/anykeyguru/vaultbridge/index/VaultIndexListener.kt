package dev.anykeyguru.vaultbridge.index

import com.intellij.util.messages.Topic

fun interface VaultIndexListener {
    fun indexChanged(state: VaultState)

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("Vault Bridge index", VaultIndexListener::class.java)
    }
}

data class VaultState(
    val root: String?,
    val index: VaultIndex,
    val indexing: Boolean,
    val error: String? = null,
) {
    val name: String? get() = root?.trimEnd('/')?.substringAfterLast('/')
}
