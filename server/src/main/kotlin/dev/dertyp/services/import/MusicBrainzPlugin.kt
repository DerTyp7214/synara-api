package dev.dertyp.services.import

import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.ISynaraPlugin
import dev.dertyp.plugins.PluginContext

class MusicBrainzPlugin : ISynaraPlugin {
    override val id: String = "musicbrainz"
    override val name: String = "MusicBrainz"

    private lateinit var importer: MusicBrainzImporter

    override fun init(context: PluginContext) {
        importer = MusicBrainzImporter(context)
        importer.indexer = context.indexer
    }

    override fun getImporters(): List<IImporter> = listOf(importer)
}
