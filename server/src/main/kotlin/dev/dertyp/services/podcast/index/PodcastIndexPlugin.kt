package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.IContentSourcePlugin
import dev.dertyp.plugins.IPodcastIndex
import dev.dertyp.plugins.IUiPlugin
import dev.dertyp.plugins.PluginContext
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.plugins.UiContribution
import io.ktor.server.application.ApplicationEnvironment
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PodcastIndexPlugin : IContentSourcePlugin, IUiPlugin, KoinComponent {
    override val id: String = "podcastindex"
    override val name: String = "Podcast Index"
    override val apiVersion: Int = 2

    private val environment by inject<ApplicationEnvironment>()
    private lateinit var settings: PluginSettings
    private lateinit var credentials: PodcastIndexCredentialSource
    private lateinit var index: PodcastIndexOrgIndex

    override fun init(context: PluginContext) {
        settings = context.settings
        credentials = PodcastIndexCredentialSource(settings, environment.config)
        index = PodcastIndexOrgIndex(credentials)
        context.i18n.registerBundlesFromResources(javaClass.classLoader, "i18n/podcastindex", listOf("en", "de"))
    }

    override fun getPodcastIndexes(): List<IPodcastIndex> = listOf(index)

    override fun getUiContributions(): List<UiContribution> = listOf(PodcastIndexCredentialsContribution(credentials, settings))
}
