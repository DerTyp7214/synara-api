package dev.dertyp.services.lyrics.providers

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class ProviderHttpRulesTest : ProviderTestSupport() {

    private fun provider(id: String): LyricsProvider = koin<LyricsProviderRegistry>().provider(id)!!

    @ParameterizedTest
    @MethodSource("missCases")
    fun `unauthorized forbidden not found and gone are misses`(id: String, status: Int) {
        handler = { respond("", HttpStatusCode.fromValue(status)) }
        assertEquals(ProviderResult.Miss, fetch(provider(id)))
        assertEquals(1, requests.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["betterlyrics", "unison", "lyricsplus", "kugou", "lrclib", "youtubemusic"])
    fun `server errors are errors`(id: String) {
        handler = { respond("boom", HttpStatusCode.InternalServerError) }
        assertInstanceOf(ProviderResult.Error::class.java, fetch(provider(id)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["betterlyrics", "unison", "lyricsplus", "kugou", "lrclib", "youtubemusic"])
    fun `oversized bodies are errors`(id: String) {
        handler = { json(OVERSIZED) }
        val result = fetch(provider(id))
        assertInstanceOf(ProviderResult.Error::class.java, result)
        assertTrue((result as ProviderResult.Error).cause.message!!.contains("exceeds"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["betterlyrics", "unison", "lyricsplus", "kugou", "lrclib", "youtubemusic"])
    fun `cross host redirects are refused without contacting the other host`(id: String) {
        handler = { request ->
            if (request.url.host == "elsewhere.example") json("{}")
            else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/steal"))
        }
        val result = fetch(provider(id))
        assertInstanceOf(ProviderResult.Error::class.java, result)
        assertTrue((result as ProviderResult.Error).cause.message!!.contains("Refused redirect"))
        assertTrue(requests.none { it.url.host == "elsewhere.example" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["betterlyrics", "unison", "lyricsplus", "kugou", "lrclib", "youtubemusic"])
    fun `malformed bodies are errors`(id: String) {
        handler = { json("not json") }
        assertInstanceOf(ProviderResult.Error::class.java, fetch(provider(id)))
    }

    companion object {
        @JvmStatic
        fun missCases(): List<Arguments> = PROVIDER_IDS.flatMap { id ->
            listOf(401, 403, 404, 410).map { Arguments.of(id, it) }
        }

        private val PROVIDER_IDS = listOf("betterlyrics", "unison", "lyricsplus", "kugou", "lrclib", "youtubemusic")
    }
}
