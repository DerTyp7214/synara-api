package dev.dertyp.services.youtube

import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.ISynaraPlugin
import dev.dertyp.plugins.PluginContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

class YoutubePlugin : ISynaraPlugin, KoinComponent {
    override val id: String = "youtube"
    override val name: String = "YouTube"
    override val enabled: Boolean get() = youtubeService.enabled

    private val youtubeService: YoutubeService by inject()
    private lateinit var indexer: YoutubeIndexer

    override fun init(context: PluginContext) {
        indexer = YoutubeIndexer(context)
        youtubeService.indexer = indexer
    }

    override fun getKoinModule(): Module = module {
        singleOf(::YoutubeApiService)
        singleOf(::YoutubeService)
    }

    override fun getImporter(): IImporter = youtubeService
    override fun getIndexer(): IPluginIndexer = indexer
}
