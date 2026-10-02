package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.IContentSourcePlugin
import dev.dertyp.plugins.IPodcastIndex
import dev.dertyp.plugins.PluginContext

class PodcastIndexPlugin : IContentSourcePlugin {
    override val id: String = PodcastIndexCredentialSource.PLUGIN_ID
    override val name: String = "Podcast Index"
    override val apiVersion: Int = 2

    private lateinit var index: PodcastIndexOrgIndex

    override fun init(context: PluginContext) {
        index = PodcastIndexOrgIndex()
    }

    override fun getPodcastIndexes(): List<IPodcastIndex> = listOf(index)
}
