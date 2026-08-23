package dev.dertyp.services

import dev.dertyp.core.ApiVersion
import dev.dertyp.data.HandshakeResponse
import io.ktor.server.application.ApplicationCall

class HandshakeService(private val call: ApplicationCall) : IHandshakeService {
    override suspend fun handshake(): HandshakeResponse {
        return determineHandshakeResponse(call)
    }

    companion object {
        fun determineHandshakeResponse(call: ApplicationCall): HandshakeResponse {
            val secure = call.request.local.scheme == "https" || 
                        call.request.local.scheme == "wss" ||
                        call.request.headers["X-Forwarded-Proto"] == "https" ||
                        call.request.headers["X-Forwarded-Proto"] == "wss"
            
            val config = call.application.environment.config
            val serverSslSupported = config.propertyOrNull("server.sslSupported")?.getString()?.toBoolean() ?: false
            
            val sslSupported = secure || serverSslSupported
            
            return HandshakeResponse(secure = secure, sslSupported = sslSupported, apiVersion = ApiVersion.CURRENT)
        }
    }
}
