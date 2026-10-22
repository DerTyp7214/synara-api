package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.Service
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

class LyricsPlusProvider : LyricsProvider, Service() {
    private val http by inject<LyricsHttp>()

    override val id = ID
    override val name = "LyricsPlus"
    override val url = "https://lyricsplus.binimum.org"
    override val usuallyWordSynced = true

    override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult =
        http.get(ENDPOINT, TIMEOUT, priority) {
            parameter("title", req.title)
            parameter("artist", req.artist)
            req.album?.let { parameter("album", it) }
            req.durationSec?.let { parameter("duration", it) }
        }.toResult { body ->
            val json = body.text()
            val lyrics = AppJson.parseToJsonElement(json).jsonObject["lyrics"] as? JsonArray
            if (lyrics.isNullOrEmpty()) ProviderResult.Miss else ProviderResult.Found(LyricsPayload.LyricsPlusJson(json))
        }

    companion object {
        const val ID = "lyricsplus"
        const val ENDPOINT = "https://lyricsplus.binimum.org/v2/lyrics/get"
        private val TIMEOUT = 4.seconds
    }
}
