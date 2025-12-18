package dev.dertyp

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import dev.dertyp.core.anyHeader
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.plugins.redisCache
import dev.dertyp.services.JwtService
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.seconds

fun Application.configureHTTP() {
    install(CORS) {
        anyHeader(true)
        anyMethod()
        anyHost()
    }
    install(SimpleCache) {
        if (!environment.config.propertyOrNull("redis.host")?.getString().isNullOrBlank()) {
            println("Using redis for cache!")
            redisCache {
                val config by inject<RedisCacheProvider.Config>()

                port = config.port
                ssl = config.ssl
                host = config.host
                invalidateAt = config.invalidateAt
            }
        } else {
            println("Using memory for cache!")
            memoryCache {
                invalidateAt = 10.seconds
            }
        }
    }

    getKoin().get<JwtService>().authenticate(this)
}
