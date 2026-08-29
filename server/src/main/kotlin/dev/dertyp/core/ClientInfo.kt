package dev.dertyp.core

import dev.dertyp.data.ApiVersion
import dev.dertyp.ui.UiSchemaVersion
import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import io.ktor.server.application.ApplicationCall

enum class ClientFeature(val minApiVersion: Int, val maxApiVersion: Int? = null) {
    LOSSLESS_WAV_AIFF(2),
    DOLBY_ATMOS(3),
    AUDIO_INFO(4),
    SERVER_DRIVEN_UI(5),
}

data class ClientInfo(
    val apiVersion: Int,
    val uiSchemaVersion: Int = UiSchemaVersion.NONE,
    val locale: String = DEFAULT_LOCALE,
) {
    fun supports(feature: ClientFeature): Boolean =
        apiVersion >= feature.minApiVersion && (feature.maxApiVersion == null || apiVersion <= feature.maxApiVersion)

    fun supportsUiSchema(version: Int): Boolean = uiSchemaVersion >= version

    companion object {
        const val DEFAULT_LOCALE = "en"

        val LEGACY = ClientInfo(ApiVersion.LEGACY)

        fun fromHeader(value: String?): ClientInfo = fromHeaders(value, null, null)

        fun fromHeaders(apiVersion: String?, uiSchemaVersion: String?, acceptLanguage: String?): ClientInfo = ClientInfo(
            apiVersion = apiVersion?.trim()?.toIntOrNull()?.takeIf { it >= ApiVersion.LEGACY } ?: ApiVersion.LEGACY,
            uiSchemaVersion = uiSchemaVersion?.trim()?.toIntOrNull()?.takeIf { it >= UiSchemaVersion.NONE } ?: UiSchemaVersion.NONE,
            locale = parseLocale(acceptLanguage),
        )

        fun parseLocale(acceptLanguage: String?): String {
            if (acceptLanguage.isNullOrBlank()) return DEFAULT_LOCALE
            return parseHeaderValue(acceptLanguage)
                .filter { it.value.isNotBlank() && it.value != "*" && it.quality > 0.0 }
                .maxByOrNull { it.quality }
                ?.value
                ?.trim()
                ?.lowercase()
                ?: DEFAULT_LOCALE
        }

        fun from(call: ApplicationCall): ClientInfo = fromHeaders(
            call.request.headers[ApiVersion.HEADER],
            call.request.headers[UiSchemaVersion.HEADER],
            call.request.headers[HttpHeaders.AcceptLanguage],
        )
    }
}

val ApplicationCall.clientInfo: ClientInfo get() = ClientInfo.from(this)
