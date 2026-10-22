package dev.dertyp.services.lyrics.providers

import dev.dertyp.config.LyricsProvidersConfig
import dev.dertyp.config.ServerConfig
import dev.dertyp.services.Service
import dev.dertyp.services.models.LyricsProviderInfo
import org.koin.core.component.inject

class LyricsProviderRegistry : Service() {
    private val config by inject<ServerConfig>()

    private val known: Map<String, LyricsProvider> by lazy {
        getKoin().getAll<LyricsProvider>().associateBy { it.id }
    }

    private val enabledProviders: List<LyricsProvider> by lazy {
        config.providers.lyrics.providers.mapNotNull { id ->
            known[id] ?: null.also { logger.warn("Unknown lyrics provider '$id' in ${LyricsProvidersConfig.PROVIDERS_PATH}, ignoring it") }
        }
    }

    private val allProviders: List<LyricsProvider> by lazy {
        val defaults = LyricsProvidersConfig.DEFAULT_PROVIDERS
        enabledProviders + known.values
            .filter { it !in enabledProviders }
            .sortedWith(compareBy({ defaults.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }, { it.id }))
    }

    fun enabled(): List<LyricsProvider> = enabledProviders

    fun all(): List<LyricsProvider> = allProviders

    fun provider(id: String): LyricsProvider? = known[id]

    fun priority(id: String): Int? = enabledProviders.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    fun info(provider: LyricsProvider): LyricsProviderInfo {
        val priority = priority(provider.id)
        return LyricsProviderInfo(
            id = provider.id,
            name = provider.name,
            url = provider.url,
            enabled = priority != null,
            priority = priority ?: allProviders.indexOf(provider).takeIf { it >= 0 } ?: allProviders.size,
        )
    }

    fun infos(): List<LyricsProviderInfo> = allProviders.map(::info)
}
