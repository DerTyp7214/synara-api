package dev.dertyp.services.lyrics.providers

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.Base64

class KuGouProviderTest : ProviderTestSupport() {
    private val krcBytes = byteArrayOf(0x6b, 0x72, 0x63, 0x31, 0x01, 0x02, 0x7f, -0x80)
    private val songSearch = """{"status":1,"data":{"info":[
        {"hash":"AAA","songname":"Paper Lanterns (Live)","singername":"Mira Vale","duration":260,"album_name":"Live"},
        {"hash":"BBB","songname":"Paper Lanterns","singername":"Mira Vale","duration":213,"album_name":"Night Market"}
    ]}}"""
    private val candidates = """{"status":200,"candidates":[
        {"id":"11","accesskey":"K11","song":"Paper Lanterns","singer":"Mira Vale","duration":230000},
        {"id":"22","accesskey":"K22","song":"Paper Lanterns","singer":"Mira Vale","duration":214300}
    ]}"""
    private var krcContent: String? = Base64.getEncoder().encodeToString(krcBytes)
    private var lrcContent: String? = null

    private fun installHandler() {
        handler = { request ->
            when (request.url.host) {
                "mobilecdn.kugou.com" -> json(songSearch)
                "krcs.kugou.com" -> json(candidates)
                "lyrics.kugou.com" -> {
                    val content = if (request.param("fmt") == "krc") krcContent else lrcContent
                    if (content == null) json("""{"status":404,"info":"not found"}""")
                    else json("""{"status":200,"fmt":"${request.param("fmt")}","content":"$content"}""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `downloads the krc of the closest song and candidate`() {
        installHandler()
        val result = fetch(koin<KuGouProvider>())
        assertInstanceOf(ProviderResult.Found::class.java, result)
        result as ProviderResult.Found
        assertArrayEquals(krcBytes, (result.payload as LyricsPayload.Krc).bytes)
        assertEquals("Paper Lanterns", result.trackTitle)
        assertEquals("Mira Vale", result.trackArtist)
        assertEquals("Night Market", result.trackAlbum)
        assertEquals(214, result.durationSec)
        assertEquals(true, result.verifiedMetadata)

        val (search, lyricSearch, download) = requests
        assertEquals("/api/v3/search/song", search.url.encodedPath)
        assertEquals("Mira Vale Paper Lanterns", search.param("keyword"))
        assertEquals("BBB", lyricSearch.param("hash"))
        assertEquals("213000", lyricSearch.param("duration"))
        assertEquals("Mira Vale - Paper Lanterns", lyricSearch.param("keyword"))
        assertEquals("22", download.param("id"))
        assertEquals("K22", download.param("accesskey"))
        assertEquals("krc", download.param("fmt"))
        assertEquals(3, requests.size)
    }

    @Test
    fun `falls back to lrc when there is no krc`() {
        krcContent = null
        lrcContent = Base64.getEncoder().encodeToString("﻿[00:01.00]Paper lanterns".encodeToByteArray())
        installHandler()
        val result = fetch(koin<KuGouProvider>()) as ProviderResult.Found
        assertEquals(LyricsPayload.Lrc("[00:01.00]Paper lanterns"), result.payload)
        assertEquals(listOf("krc", "lrc"), requests.filter { it.url.host == "lyrics.kugou.com" }.map { it.param("fmt") })
    }

    @Test
    fun `no download in either format is a miss`() {
        krcContent = null
        installHandler()
        assertEquals(ProviderResult.Miss, fetch(koin<KuGouProvider>()))
    }

    @Test
    fun `no lyric candidates is a miss`() {
        handler = { request ->
            if (request.url.host == "mobilecdn.kugou.com") json(songSearch) else json("""{"status":200,"candidates":[]}""")
        }
        assertEquals(ProviderResult.Miss, fetch(koin<KuGouProvider>()))
        assertEquals(2, requests.size)
    }

    @Test
    fun `no songs is a miss`() {
        handler = { json("""{"status":1,"data":{"info":[]}}""") }
        assertEquals(ProviderResult.Miss, fetch(koin<KuGouProvider>()))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a failing lyric search is an error`() {
        handler = { request ->
            if (request.url.host == "mobilecdn.kugou.com") json(songSearch) else respond("", HttpStatusCode.BadGateway)
        }
        assertInstanceOf(ProviderResult.Error::class.java, fetch(koin<KuGouProvider>()))
    }
}
