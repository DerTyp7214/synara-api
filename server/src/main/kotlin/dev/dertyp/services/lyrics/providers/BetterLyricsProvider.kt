package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.Service
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

class BetterLyricsProvider : LyricsProvider, Service() {
    private val http by inject<LyricsHttp>()

    override val id = ID
    override val name = "Better Lyrics"
    override val url = "https://github.com/better-lyrics/better-lyrics"
    override val usuallyWordSynced = true

    override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult =
        http.get(ENDPOINT, TIMEOUT, priority) {
            parameter("s", req.title)
            parameter("a", req.artist)
            req.album?.let { parameter("al", it) }
            req.durationSec?.let { parameter("d", it) }
        }.toResult { body ->
            val ttml = AppJson.decodeFromString<Response>(body.text()).ttml
            if (ttml.isNullOrBlank()) ProviderResult.Miss else ProviderResult.Found(LyricsPayload.Ttml(ttml))
        }

    @Serializable
    private data class Response(val ttml: String? = null)

    companion object {
        const val ID = "betterlyrics"
        const val ENDPOINT = "https://lyrics-api.boidu.dev/getLyrics"
        private val TIMEOUT = 4.seconds
    }
}
