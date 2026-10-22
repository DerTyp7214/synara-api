package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.Service
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

class UnisonProvider : LyricsProvider, Service() {
    private val http by inject<LyricsHttp>()

    override val id = ID
    override val name = "Unison"
    override val url = "https://unison.betterlyrics.org"
    override val usuallyWordSynced = true

    override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult =
        http.get(ENDPOINT, TIMEOUT, priority) {
            parameter("song", req.title)
            parameter("artist", req.artist)
            req.album?.let { parameter("album", it) }
            req.durationSec?.let { parameter("duration", it) }
        }.toResult { body ->
            val data = AppJson.decodeFromString<Response>(body.text()).data
            val lyrics = data?.lyrics?.takeIf { it.isNotBlank() } ?: return@toResult ProviderResult.Miss
            val payload = if (data.format.equals("ttml", ignoreCase = true)) {
                LyricsPayload.Ttml(lyrics)
            } else {
                LyricsPayload.Lrc(lyrics)
            }
            ProviderResult.Found(payload)
        }

    @Serializable
    private data class Response(val data: Data? = null)

    @Serializable
    private data class Data(val lyrics: String? = null, val format: String? = null)

    companion object {
        const val ID = "unison"
        const val ENDPOINT = "https://unison.betterlyrics.org/lyrics"
        private val TIMEOUT = 8.seconds
    }
}
