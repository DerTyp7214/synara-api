package dev.dertyp.services.lyrics.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LyricsPlusProviderTest : ProviderTestSupport() {
    private val body = """{"type":"Word","metadata":{"title":"Paper Lanterns"},"lyrics":[{"time":1000,"duration":900,"text":"Paper","syllabus":[{"time":1000,"duration":900,"text":"Paper","isBackground":false}]}]}"""

    @Test
    fun `returns the raw json`() {
        handler = { json(body) }
        assertEquals(ProviderResult.Found(LyricsPayload.LyricsPlusJson(body)), fetch(koin<LyricsPlusProvider>()))
        val request = requests.single()
        assertEquals("lyricsplus.binimum.org", request.url.host)
        assertEquals("/v2/lyrics/get", request.url.encodedPath)
        assertEquals("Paper Lanterns", request.param("title"))
        assertEquals("Mira Vale", request.param("artist"))
        assertEquals("Night Market", request.param("album"))
        assertEquals("214", request.param("duration"))
    }

    @Test
    fun `an answer without lyrics is a miss`() {
        handler = { json("""{"error":{"message":"Lyrics not found","status":404}}""") }
        assertEquals(ProviderResult.Miss, fetch(koin<LyricsPlusProvider>()))
    }

    @Test
    fun `an empty lyrics list is a miss`() {
        handler = { json("""{"type":"Line","lyrics":[]}""") }
        assertEquals(ProviderResult.Miss, fetch(koin<LyricsPlusProvider>()))
    }
}
