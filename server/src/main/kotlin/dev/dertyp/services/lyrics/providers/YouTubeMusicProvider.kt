package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.Service
import io.ktor.client.request.parameter
import kotlinx.serialization.json.*
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

class YouTubeMusicProvider : LyricsProvider, Service() {
    private val http by inject<LyricsHttp>()

    override val id = ID
    override val name = "YouTube Music"
    override val url = "https://music.youtube.com"
    override val usuallyWordSynced = false

    private data class Track(
        val videoId: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationSec: Int?,
    )

    override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult {
        val search = buildJsonObject {
            put("context", context(WEB_CLIENT, WEB_VERSION))
            put("query", "${req.title} ${req.artist}")
            put("params", SONGS_FILTER)
        }
        return http.postJson("$API/search", search, TIMEOUT, priority) { parameter("key", API_KEY) }
            .toResult { searchBody ->
                val track = parseTracks(AppJson.parseToJsonElement(searchBody.text()))
                    .take(SEARCH_CANDIDATES)
                    .closestByDuration(req.durationSec) { it.durationSec }
                    ?: return@toResult ProviderResult.Miss
                lyricsFor(track, priority)
            }
    }

    private suspend fun lyricsFor(track: Track, priority: HttpClientPriority): ProviderResult {
        val next = buildJsonObject {
            put("context", context(WEB_CLIENT, WEB_VERSION))
            put("videoId", track.videoId)
            put("isAudioOnly", true)
        }
        return http.postJson("$API/next", next, TIMEOUT, priority) { parameter("key", API_KEY) }
            .toResult { nextBody ->
                val browseId = lyricsBrowseId(AppJson.parseToJsonElement(nextBody.text()))
                    ?: return@toResult ProviderResult.Miss
                val payload = timedLyrics(browseId, priority) ?: when (val plain = plainLyrics(browseId, priority)) {
                    is PlainOutcome.Text -> LyricsPayload.Plain(plain.text)
                    PlainOutcome.None -> return@toResult ProviderResult.Miss
                    is PlainOutcome.Failed -> return@toResult ProviderResult.Error(plain.cause)
                }
                ProviderResult.Found(
                    payload = payload,
                    trackTitle = track.title,
                    trackArtist = track.artist,
                    trackAlbum = track.album,
                    durationSec = track.durationSec,
                    verifiedMetadata = true,
                )
            }
    }

    private suspend fun timedLyrics(browseId: String, priority: HttpClientPriority): LyricsPayload.TimedRows? {
        val body = buildJsonObject {
            put("context", context(ANDROID_CLIENT, ANDROID_VERSION))
            put("browseId", browseId)
        }
        val response = http.postJson("$API/browse", body, TIMEOUT, priority) as? HttpFetch.Body ?: return null
        val data = runCatching { AppJson.parseToJsonElement(response.text()) }.getOrNull()
            .at(*TIMED_LYRICS_PATH, "timedLyricsData") as? JsonArray ?: return null
        val rows = data.map { entry ->
            val cue = entry.at("cueRange") as? JsonObject ?: return null
            val start = cue["startTimeMilliseconds"].longValue() ?: return null
            TimedRow(start, cue["endTimeMilliseconds"].longValue(), entry.at("lyricLine").stringValue().orEmpty())
        }
        return rows.takeIf { it.isNotEmpty() }?.let { LyricsPayload.TimedRows(it) }
    }

    private sealed interface PlainOutcome {
        data class Text(val text: String) : PlainOutcome
        data object None : PlainOutcome
        data class Failed(val cause: Throwable) : PlainOutcome
    }

    private suspend fun plainLyrics(browseId: String, priority: HttpClientPriority): PlainOutcome {
        val body = buildJsonObject {
            put("context", context(WEB_CLIENT, WEB_VERSION))
            put("browseId", browseId)
        }
        return when (val response = http.postJson("$API/browse", body, TIMEOUT, priority) { parameter("key", API_KEY) }) {
            HttpFetch.Missing -> PlainOutcome.None
            is HttpFetch.Failed -> PlainOutcome.Failed(response.cause)
            is HttpFetch.Body -> {
                val runs = runCatching { AppJson.parseToJsonElement(response.text()) }.getOrElse { return PlainOutcome.Failed(it) }
                    .at("contents", "sectionListRenderer", "contents", 0, "musicDescriptionShelfRenderer", "description", "runs")
                    as? JsonArray
                val text = runs?.joinToString("") { it.at("text").stringValue().orEmpty() }
                if (text.isNullOrBlank()) PlainOutcome.None else PlainOutcome.Text(text)
            }
        }
    }

    private fun parseTracks(root: JsonElement): List<Track> {
        val sections = root.at("contents", "tabbedSearchResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents")
            as? JsonArray ?: return emptyList()
        return sections.flatMap { section ->
            (section.at("musicShelfRenderer", "contents") as? JsonArray).orEmpty()
        }.mapNotNull { item ->
            val renderer = item.at("musicResponsiveListItemRenderer") ?: return@mapNotNull null
            val videoId = renderer.at("playlistItemData", "videoId").stringValue() ?: return@mapNotNull null
            val title = renderer.at("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer", "text", "runs", 0, "text").stringValue()
            val runs = (renderer.at("flexColumns", 1, "musicResponsiveListItemFlexColumnRenderer", "text", "runs") as? JsonArray).orEmpty()
            val artists = runs.filter { pageType(it) == ARTIST_PAGE }.mapNotNull { it.at("text").stringValue() }
            val segments = runs.joinToString("") { it.at("text").stringValue().orEmpty() }.split(SEPARATOR)
            Track(
                videoId = videoId,
                title = title,
                artist = artists.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: segments.firstOrNull()?.ifBlank { null },
                album = runs.firstOrNull { pageType(it) == ALBUM_PAGE }?.at("text").stringValue(),
                durationSec = segments.lastOrNull()?.let(::parseClock),
            )
        }
    }

    private fun lyricsBrowseId(root: JsonElement): String? {
        val tabs = root.at(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs"
        ) as? JsonArray ?: return null
        return tabs.firstNotNullOfOrNull { tab ->
            val endpoint = tab.at("tabRenderer", "endpoint", "browseEndpoint") ?: return@firstNotNullOfOrNull null
            endpoint.at("browseId").stringValue().takeIf { pageType(endpoint, nested = false) == LYRICS_PAGE }
        }
    }

    private fun pageType(element: JsonElement, nested: Boolean = true): String? {
        val endpoint = if (nested) element.at("navigationEndpoint", "browseEndpoint") else element
        return endpoint.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").stringValue()
    }

    private fun parseClock(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        return parts.fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: return null) }
    }

    private fun context(client: String, version: String) = buildJsonObject {
        putJsonObject("client") {
            put("clientName", client)
            put("clientVersion", version)
            put("hl", "en")
        }
    }

    private fun JsonElement?.at(vararg path: Any): JsonElement? = path.fold(this) { current, key ->
        when (key) {
            is String -> (current as? JsonObject)?.get(key)
            is Int -> (current as? JsonArray)?.getOrNull(key)
            else -> null
        }
    }

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.longValue(): Long? = (this as? JsonPrimitive)?.content?.toLongOrNull()

    companion object {
        const val ID = "youtubemusic"
        const val API = "https://music.youtube.com/youtubei/v1"
        private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        private const val WEB_CLIENT = "WEB_REMIX"
        private const val WEB_VERSION = "1.20261020.10.00"
        private const val ANDROID_CLIENT = "ANDROID_MUSIC"
        private const val ANDROID_VERSION = "7.21.50"
        private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        private const val SEPARATOR = " • "
        private const val SEARCH_CANDIDATES = 5
        private const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
        private const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"
        private const val LYRICS_PAGE = "MUSIC_PAGE_TYPE_TRACK_LYRICS"
        private val TIMED_LYRICS_PATH = arrayOf<Any>(
            "contents", "elementRenderer", "newElement", "type", "componentType", "model", "timedLyricsModel", "lyricsData"
        )
        private val TIMEOUT = 4.seconds
    }
}
