package dev.dertyp.plugins

import dev.dertyp.services.metadata.IMetadataService
import org.koin.core.module.Module

interface ISynaraPlugin {
    val id: String
    val name: String
    val apiVersion: Int get() = 1
    val enabled: Boolean get() = true

    fun init(context: PluginContext)

    fun getKoinModule(): Module? = null
    fun getDownloader(): IDownloader? = null
    fun getDownloaders(): List<IDownloader> = getDownloader()?.let { listOf(it) } ?: emptyList()
    fun getIndexer(): IPluginIndexer? = null
    fun getIndexers(): List<IPluginIndexer> = getIndexer()?.let { listOf(it) } ?: emptyList()
    fun getMetadataService(type: IMetadataService.MetadataType): IMetadataService? = null
}
