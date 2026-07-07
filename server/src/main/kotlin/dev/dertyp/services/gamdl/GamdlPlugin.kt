package dev.dertyp.services.gamdl

import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.ISynaraPlugin
import dev.dertyp.plugins.PluginContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

class GamdlPlugin : ISynaraPlugin, KoinComponent {
    override val id: String = "gamdl"
    override val name: String = "gamdl (Apple Music)"
    override val enabled: Boolean get() = gamdlService.enabled

    private val gamdlService: GamdlService by inject()
    private lateinit var indexer: GamdlIndexer

    override fun init(context: PluginContext) {
        indexer = GamdlIndexer(context)
        gamdlService.indexer = indexer
    }

    override fun getKoinModule(): Module = module {
        singleOf(::GamdlService)
    }

    override fun getImporter(): IImporter = gamdlService
    override fun getIndexer(): IPluginIndexer = indexer
}
