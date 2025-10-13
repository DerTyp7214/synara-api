package dev.dertyp

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import dev.dertyp.services.JwtService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureHTTP(jwtService: JwtService) {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        anyHost()
    }
    install(SimpleCache) {
        memoryCache {
            invalidateAt = 10.seconds
        }
    }

    jwtService.authenticate(this)
}
