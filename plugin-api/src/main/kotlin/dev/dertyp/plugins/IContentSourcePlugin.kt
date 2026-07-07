package dev.dertyp.plugins

import dev.dertyp.services.metadata.IMetadataService

interface IContentSourcePlugin : ISynaraPlugin {
    fun getImporter(): IImporter? = null
    fun getImporters(): List<IImporter> = getImporter()?.let { listOf(it) } ?: emptyList()
    fun getIndexer(): IPluginIndexer? = null
    fun getIndexers(): List<IPluginIndexer> = getIndexer()?.let { listOf(it) } ?: emptyList()
    fun getMetadataService(type: IMetadataService.MetadataType): IMetadataService? = null
}
