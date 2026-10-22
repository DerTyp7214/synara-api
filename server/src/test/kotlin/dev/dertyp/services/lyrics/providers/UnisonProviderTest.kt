package dev.dertyp.services.lyrics.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnisonProviderTest : ProviderTestSupport() {

    @Test
    fun `returns ttml when the format is ttml`() {
        handler = { json("""{"data":{"lyrics":"<tt/>","format":"ttml"}}""") }
        assertEquals(ProviderResult.Found(LyricsPayload.Ttml("<tt/>")), fetch(koin<UnisonProvider>()))
        val request = requests.single()
        assertEquals("unison.betterlyrics.org", request.url.host)
        assertEquals("/lyrics", request.url.encodedPath)
        assertEquals("Paper Lanterns", request.param("song"))
        assertEquals("Mira Vale", request.param("artist"))
        assertEquals("Night Market", request.param("album"))
        assertEquals("214", request.param("duration"))
    }

    @Test
    fun `returns lrc for other formats`() {
        handler = { json("""{"data":{"lyrics":"[00:01.00]Paper lanterns","format":"lrc"}}""") }
        assertEquals(ProviderResult.Found(LyricsPayload.Lrc("[00:01.00]Paper lanterns")), fetch(koin<UnisonProvider>()))
    }

    @Test
    fun `missing data is a miss`() {
        handler = { json("""{"success":false,"code":"NOT_FOUND"}""") }
        assertEquals(ProviderResult.Miss, fetch(koin<UnisonProvider>()))
    }
}
