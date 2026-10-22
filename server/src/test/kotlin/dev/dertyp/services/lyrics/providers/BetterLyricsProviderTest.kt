package dev.dertyp.services.lyrics.providers

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BetterLyricsProviderTest : ProviderTestSupport() {
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1.0" end="2.0"><span begin="1.0" end="1.5">Paper</span></p></div></body></tt>"""

    @Test
    fun `returns the ttml and sends the query`() {
        handler = { json("""{"ttml":${JsonPrimitive(ttml)}}""") }
        val result = fetch(koin<BetterLyricsProvider>())
        assertEquals(ProviderResult.Found(LyricsPayload.Ttml(ttml)), result)
        val request = requests.single()
        assertEquals("lyrics-api.boidu.dev", request.url.host)
        assertEquals("/getLyrics", request.url.encodedPath)
        assertEquals("Paper Lanterns", request.param("s"))
        assertEquals("Mira Vale", request.param("a"))
        assertEquals("Night Market", request.param("al"))
        assertEquals("214", request.param("d"))
        assertEquals(true, request.headers[HttpHeaders.UserAgent]?.startsWith("Synara/"))
    }

    @Test
    fun `leaves out album and duration when unknown`() {
        handler = { json("""{"ttml":"<tt/>"}""") }
        fetch(koin<BetterLyricsProvider>(), REQUEST.copy(album = null, durationSec = null))
        val request = requests.single()
        assertEquals(null, request.param("al"))
        assertEquals(null, request.param("d"))
    }

    @Test
    fun `blank ttml is a miss`() {
        handler = { json("""{"ttml":""}""") }
        assertEquals(ProviderResult.Miss, fetch(koin<BetterLyricsProvider>()))
    }

    @Test
    fun `follows redirects on the same host`() {
        handler = { request ->
            if (request.url.encodedPath == "/getLyrics") {
                respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/v2/getLyrics?s=x"))
            } else {
                json("""{"ttml":"<tt/>"}""")
            }
        }
        assertEquals(ProviderResult.Found(LyricsPayload.Ttml("<tt/>")), fetch(koin<BetterLyricsProvider>()))
        assertEquals(listOf("/getLyrics", "/v2/getLyrics"), requests.map { it.url.encodedPath })
    }
}
