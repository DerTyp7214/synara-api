package dev.dertyp.services.lyrics.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LrcLibLyricsProviderTest : ProviderTestSupport() {

    private fun entry(id: Int, duration: Double, synced: String?, plain: String?, instrumental: Boolean = false) =
        """{"id":$id,"trackName":"Paper Lanterns","artistName":"Mira Vale","albumName":"Night Market","duration":$duration,
            "instrumental":$instrumental,"plainLyrics":${plain?.let { "\"$it\"" } ?: "null"},"syncedLyrics":${synced?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun `searches and picks the closest duration preferring synced lyrics`() {
        handler = {
            json(
                "[" + listOf(
                    entry(1, 250.0, "[00:01.00]far", "far"),
                    entry(2, 214.2, null, "close plain"),
                    entry(3, 213.8, "[00:01.00]close synced", "close synced"),
                ).joinToString(",") + "]"
            )
        }
        val result = fetch(koin<LrcLibLyricsProvider>()) as ProviderResult.Found
        assertEquals(LyricsPayload.Lrc("[00:01.00]close synced"), result.payload)
        assertEquals("Paper Lanterns", result.trackTitle)
        assertEquals("Mira Vale", result.trackArtist)
        assertEquals("Night Market", result.trackAlbum)
        assertEquals(214, result.durationSec)
        assertEquals(true, result.verifiedMetadata)
        val request = requests.single()
        assertEquals("lrclib.net", request.url.host)
        assertEquals("/api/search", request.url.encodedPath)
        assertEquals("Paper Lanterns", request.param("track_name"))
        assertEquals("Mira Vale", request.param("artist_name"))
    }

    @Test
    fun `plain lyrics when nothing is synced`() {
        handler = { json("[" + entry(1, 214.0, null, "only plain") + "]") }
        assertEquals(LyricsPayload.Plain("only plain"), (fetch(koin<LrcLibLyricsProvider>()) as ProviderResult.Found).payload)
    }

    @Test
    fun `instrumental tracks are found as instrumental`() {
        handler = { json("[" + entry(1, 214.0, null, null, instrumental = true) + "]") }
        assertEquals(LyricsPayload.Instrumental, (fetch(koin<LrcLibLyricsProvider>()) as ProviderResult.Found).payload)
    }

    @Test
    fun `no usable results is a miss`() {
        handler = { json("[" + entry(1, 214.0, null, null) + "]") }
        assertEquals(ProviderResult.Miss, fetch(koin<LrcLibLyricsProvider>()))
        handler = { json("[]") }
        assertEquals(ProviderResult.Miss, fetch(koin<LrcLibLyricsProvider>()))
    }
}
