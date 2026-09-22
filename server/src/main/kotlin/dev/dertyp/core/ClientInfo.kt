package dev.dertyp.core

import dev.dertyp.data.ApiVersion
import dev.dertyp.ui.UiSchemaVersion
import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import io.ktor.server.application.ApplicationCall

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class FeatureDoc(
    val introduces: String,
    val fallback: String
)

enum class ClientFeature(val minApiVersion: Int, val maxApiVersion: Int? = null) {
    @FeatureDoc(
        introduces = "Streaming WAV and AIFF source files as they are.",
        fallback = "The server transcodes the file to FLAC before streaming it.",
    )
    LOSSLESS_WAV_AIFF(2),

    @FeatureDoc(
        introduces = "The Dolby Atmos variant of a song (E-AC-3 JOC in MP4) and the `streamSongAtmos` endpoint.",
        fallback = "The Atmos stream resolves to nothing — there is no Atmos playback, and `atmosPath` is stripped from every song.",
    )
    DOLBY_ATMOS(3),

    @FeatureDoc(
        introduces = "File properties nested in `audio` and `atmos` as [`AudioInfo`](MODELS.md#devdertypdataaudioinfo) — `codec`, `sampleRate`, `bitsPerSample`, `bitRate`, `fileSize`, `channels`.",
        fallback = "`audio` and `atmos` are cleared and their values flattened back into the deprecated top-level `sampleRate`, `bitsPerSample`, `bitRate`, `fileSize` and `atmosPath` fields.",
    )
    AUDIO_INFO(4),

    @FeatureDoc(
        introduces = "Component trees from the server, rendered natively by the client.",
        fallback = "Nothing to render; the companion `X-Ui-Schema-Version` header controls the detail.",
    )
    SERVER_DRIVEN_UI(5),

    @FeatureDoc(
        introduces = "`tags`: version markers such as *Radio Edit*, *feat. Drake* or *Live at Wembley*, split off the title into [`TitleTag`](MODELS.md#devdertypdatatitletag) entries, so `title` is clean.",
        fallback = "`title` is put back together into the full original title and `tags` is emptied.",
    )
    TITLE_TAGS(6),
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
