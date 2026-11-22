package dev.anykeyguru.vaultbridge.config.json

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import dev.anykeyguru.vaultbridge.config.ConfigParser

/** Схема для `.vaultbridge.json`: автодополнение ключей, описания при наведении, проверка значений. */
class VaultBridgeSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(Provider)

    private object Provider : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = file.name == ConfigParser.FILE_NAME
        override fun getName(): String = "Vault Bridge"
        override fun getSchemaFile(): VirtualFile? =
            JsonSchemaProviderFactory.getResourceFile(VaultBridgeSchemaProviderFactory::class.java, "/schemas/vaultbridge.schema.json")
        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
}
