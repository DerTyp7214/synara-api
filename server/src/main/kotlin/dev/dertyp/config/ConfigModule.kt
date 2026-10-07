package dev.dertyp.config

import dev.dertyp.plugins.toRedisCacheProviderConfig
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val configModule = module {
    singleOf(::ServerConfig)
    singleOf(ServerConfig::metrics)
    singleOf(ServerConfig::audio)
    singleOf(ServerConfig::cover)
    singleOf(ServerConfig::entityChanges)
    singleOf(ServerConfig::versionGroups)
    singleOf(ServerConfig::toRedisCacheProviderConfig)
}
