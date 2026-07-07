package dev.dertyp.services.soundcloud

import dev.dertyp.plugins.IContentSourcePlugin
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.PluginContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

class SoundcloudPlugin : IContentSourcePlugin, KoinComponent {
    override val id: String = "soundcloud"
    override val name: String = "SoundCloud"
    override val enabled: Boolean get() = soundcloudService.enabled

    private val soundcloudService: SoundcloudService by inject()
    private lateinit var indexer: SoundcloudIndexer

    override fun init(context: PluginContext) {
        indexer = SoundcloudIndexer(context)
        soundcloudService.indexer = indexer
    }

    override fun getKoinModule(): Module = module {
        singleOf(::SoundcloudService)
    }

    override fun getImporter(): IImporter = soundcloudService
    override fun getIndexer(): IPluginIndexer = indexer
}
