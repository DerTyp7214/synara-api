package dev.dertyp

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.anyHeader
import dev.dertyp.data.ApiVersion
import dev.dertyp.ui.UiSchemaVersion
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.plugins.redisCache
import dev.dertyp.services.JwtService
import dev.dertyp.services.RedisSearchService
import dev.dertyp.services.ServiceLifecycle
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.seconds

fun Application.configureHTTP() {
    install(CORS) {
        anyHeader(true)
        allowHeader(ApiVersion.HEADER)
        allowHeader(UiSchemaVersion.HEADER)
        anyMethod()
        anyHost()
    }
    getKoin().get<JwtService>().authenticate(this)
}

fun Application.configureCache() {
    install(SimpleCache) {
        if (getKoin().get<ServerConfig>().redis.enabled) {
            log.info("Using redis for cache!")
            redisCache {
                val config by inject<RedisCacheProvider.Config>()

                port = config.port
                ssl = config.ssl
                host = config.host
                invalidateAt = config.invalidateAt
            }
        } else {
            log.info("Using memory for cache!")
            memoryCache {
                invalidateAt = 10.seconds
            }
        }
    }
    getKoin().getOrNull<RedisCacheProvider>()?.let { ServiceLifecycle.register(it) }
    getKoin().get<RedisSearchService>().initIndex()
}
