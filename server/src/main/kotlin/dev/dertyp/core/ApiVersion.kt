package dev.dertyp.core

import io.ktor.server.application.ApplicationCall

/**
 * Server API version. Bump whenever a client-visible capability changes and add a matching [ClientFeature].
 *
 * Clients learn the server's version from the handshake and send the highest version they
 * support in the [HEADER] on every request (REST calls and the RPC WebSocket upgrade).
 * A missing header means the client predates versioning and is treated as version 1.
 */
object ApiVersion {
    const val LEGACY = 1

    const val CURRENT = 2

    const val HEADER = "X-Api-Version"
}

enum class ClientFeature(val minApiVersion: Int, val maxApiVersion: Int? = null) {
    LOSSLESS_WAV_AIFF(2),
}

data class ClientInfo(val apiVersion: Int) {
    fun supports(feature: ClientFeature): Boolean =
        apiVersion >= feature.minApiVersion && (feature.maxApiVersion == null || apiVersion <= feature.maxApiVersion)

    companion object {
        val LEGACY = ClientInfo(ApiVersion.LEGACY)

        fun fromHeader(value: String?): ClientInfo =
            ClientInfo(value?.trim()?.toIntOrNull()?.takeIf { it >= ApiVersion.LEGACY } ?: ApiVersion.LEGACY)

        fun from(call: ApplicationCall): ClientInfo = fromHeader(call.request.headers[ApiVersion.HEADER])
    }
}

val ApplicationCall.clientInfo: ClientInfo get() = ClientInfo.from(this)
