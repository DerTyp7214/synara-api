package dev.dertyp

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import com.ucasoft.ktor.simpleRedisCache.redisCache
import dev.dertyp.core.anyHeader
import dev.dertyp.services.JwtService
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureHTTP(jwtService: JwtService) {
    install(CORS) {
        anyHeader(true)
        anyMethod()
        anyHost()
    }
    install(SimpleCache) {
        if (!environment.config.propertyOrNull("redis.host")?.getString().isNullOrBlank()) {
            println("Using redis for cache!")
            redisCache {
                invalidateAt = 10.seconds
                host = environment.config.propertyOrNull("redis.host")!!.getString()
                port = environment.config.propertyOrNull("redis.port")?.getString()?.toInt() ?: port
            }
        } else {
            println("Using memory for cache!")
            memoryCache {
                invalidateAt = 10.seconds
            }
        }
    }

    jwtService.authenticate(this)
}
