package dev.dertyp.services.lyrics.providers

import dev.dertyp.serializers.AppJson
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YouTubeMusicProviderTest : ProviderTestSupport() {

    private fun item(videoId: String, title: String, duration: String) = """
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$videoId"},"flexColumns":[
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
            {"text":"Mira Vale","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
            {"text":" • "},
            {"text":"Night Market","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},
            {"text":" • "},
            {"text":"$duration"}
          ]}}}
        ]}}
    """

    private val search = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
        {"musicShelfRenderer":{"contents":[${item("far", "Paper Lanterns (Extended)", "6:01")},${item("near", "Paper Lanterns", "3:35")}]}}
    ]}}}}]}}}"""

    private val next = """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
        {"tabRenderer":{"title":"Up next"}},
        {"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"MPLYt_near","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_LYRICS"}}}}}},
        {"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"MPTRt_near","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_RELATED"}}}}}}
    ]}}}}}"""

    private val timed = """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{
        "sourceMessage":"Source: Example","timedLyricsData":[
          {"lyricLine":"Paper lanterns","cueRange":{"startTimeMilliseconds":"1200","endTimeMilliseconds":"3400"}},
          {"lyricLine":"Float away","cueRange":{"startTimeMilliseconds":"3400","endTimeMilliseconds":"5000"}}
        ]}}}}}}}}}"""

    private val plain = """{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{
        "description":{"runs":[{"text":"Paper lanterns\nFloat away"}]},"footer":{"runs":[{"text":"Source: Example"}]}}}]}}}"""

    private fun HttpRequestData.clientName(): String =
        AppJson.parseToJsonElement(bodyText()).jsonObject["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content

    private fun installHandler(timedBody: String?, plainBody: String?) {
        handler = { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "search" -> json(search)
                "next" -> json(next)
                "browse" -> {
                    val body = if (request.clientName() == "ANDROID_MUSIC") timedBody else plainBody
                    body?.let { json(it) } ?: json("{}")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `returns timed rows of the closest track`() {
        installHandler(timed, plain)
        val result = fetch(koin<YouTubeMusicProvider>()) as ProviderResult.Found
        assertEquals(
            LyricsPayload.TimedRows(listOf(TimedRow(1200, 3400, "Paper lanterns"), TimedRow(3400, 5000, "Float away"))),
            result.payload,
        )
        assertEquals("Paper Lanterns", result.trackTitle)
        assertEquals("Mira Vale", result.trackArtist)
        assertEquals("Night Market", result.trackAlbum)
        assertEquals(215, result.durationSec)
        assertEquals(true, result.verifiedMetadata)

        assertEquals(listOf("search", "next", "browse"), requests.map { it.url.encodedPath.substringAfterLast('/') })
        assertEquals(true, requests.all { it.method == HttpMethod.Post && it.url.host == "music.youtube.com" })
        val (searchRequest, nextRequest, browseRequest) = requests
        assertEquals("WEB_REMIX", searchRequest.clientName())
        assertEquals("Paper Lanterns Mira Vale", AppJson.parseToJsonElement(searchRequest.bodyText()).jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals("near", AppJson.parseToJsonElement(nextRequest.bodyText()).jsonObject["videoId"]!!.jsonPrimitive.content)
        assertEquals("MPLYt_near", AppJson.parseToJsonElement(browseRequest.bodyText()).jsonObject["browseId"]!!.jsonPrimitive.content)
        assertEquals("ANDROID_MUSIC", browseRequest.clientName())
    }

    @Test
    fun `falls back to plain lyrics without timed ones`() {
        installHandler(null, plain)
        val result = fetch(koin<YouTubeMusicProvider>()) as ProviderResult.Found
        assertEquals(LyricsPayload.Plain("Paper lanterns\nFloat away"), result.payload)
        assertEquals(listOf("ANDROID_MUSIC", "WEB_REMIX"), requests.filter { it.url.encodedPath.endsWith("browse") }.map { it.clientName() })
    }

    @Test
    fun `no lyrics at all is a miss`() {
        installHandler(null, null)
        assertEquals(ProviderResult.Miss, fetch(koin<YouTubeMusicProvider>()))
    }

    @Test
    fun `a track without a lyrics tab is a miss`() {
        handler = { request ->
            if (request.url.encodedPath.endsWith("search")) json(search)
            else json("""{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[]}}}}}""")
        }
        assertEquals(ProviderResult.Miss, fetch(koin<YouTubeMusicProvider>()))
        assertEquals(2, requests.size)
    }

    @Test
    fun `no search results is a miss`() {
        handler = { json("""{"contents":{}}""") }
        assertEquals(ProviderResult.Miss, fetch(koin<YouTubeMusicProvider>()))
        assertEquals(1, requests.size)
    }
}
