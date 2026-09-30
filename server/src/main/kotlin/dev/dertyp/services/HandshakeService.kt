package dev.dertyp.services

import dev.dertyp.config.toHttpServerConfig
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.HandshakeResponse
import dev.dertyp.ui.UiSchemaVersion
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
            
            val serverSslSupported = call.application.environment.config.toHttpServerConfig().sslSupported
            
            val sslSupported = secure || serverSslSupported
            
            return HandshakeResponse(
                secure = secure,
                sslSupported = sslSupported,
                apiVersion = ApiVersion.CURRENT,
                uiSchemaVersion = UiSchemaVersion.CURRENT,
            )
        }
    }
}
