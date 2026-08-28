package dev.dertyp.core

import dev.dertyp.data.ApiVersion
import io.ktor.server.application.ApplicationCall

enum class ClientFeature(val minApiVersion: Int, val maxApiVersion: Int? = null) {
    LOSSLESS_WAV_AIFF(2),
    DOLBY_ATMOS(3),
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
