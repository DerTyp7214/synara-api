package dev.dertyp.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val McpToolJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class McpTime(val epochMs: Long, val iso: String)

@Serializable
data class McpArtistRef(val id: String, val name: String)

@Serializable
data class McpAlbumRef(val id: String, val name: String)

@Serializable
data class McpSong(
    val id: String,
    val title: String,
    val artists: List<McpArtistRef>,
    val album: McpAlbumRef?,
    val durationMs: Long,
    val releaseDate: String?,
    val genres: List<String>,
    val isrc: String?,
    val recordingMbid: String?,
    val explicit: Boolean,
)

@Serializable
data class McpUnmatched(
    val trackName: String?,
    val artistName: String?,
    val releaseName: String?,
    val recordingMbid: String?,
    val releaseMbid: String?,
    val artistMbids: List<String>,
)

@Serializable
data class McpListen(
    val id: String,
    val listenedAt: McpTime,
    val msPlayed: Long?,
    val playedMs: Long,
    val qualified: Boolean,
    val source: String,
    val song: McpSong?,
    val unmatched: McpUnmatched?,
)

@Serializable
data class McpListensPage(
    val listens: List<McpListen>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

@Serializable
data class McpListeningSummary(
    val from: McpTime?,
    val to: McpTime?,
    val timezone: String,
    val listenCount: Long,
    val listenedMs: Long,
    val uniqueSongs: Int,
    val uniqueArtists: Int,
    val uniqueAlbums: Int,
    val firstListen: McpTime?,
    val lastListen: McpTime?,
    val hourOfDay: List<Long>,
    val dayOfWeek: List<Long>,
    val daysWithListens: Int,
)

@Serializable
data class McpTopEntry(
    val rank: Int,
    val matched: Boolean,
    val id: String?,
    val name: String,
    val artistName: String?,
    val albumName: String?,
    val mbid: String?,
    val listenCount: Long,
    val listenedMs: Long,
    val firstListen: McpTime,
    val lastListen: McpTime,
)

@Serializable
data class McpTopPage(
    val kind: String,
    val orderBy: String,
    val offset: Int,
    val limit: Int,
    val total: Int,
    val entries: List<McpTopEntry>,
)

@Serializable
data class McpTimelineBucket(
    val bucketStart: McpTime,
    val listenCount: Long,
    val listenedMs: Long,
    val uniqueSongs: Int,
)

@Serializable
data class McpTimeline(
    val bucket: String,
    val timezone: String,
    val buckets: List<McpTimelineBucket>,
)

@Serializable
data class McpNowPlaying(
    val song: McpSong,
    val startedAt: McpTime,
    val positionMs: Long,
    val playing: Boolean,
)

@Serializable
data class McpSearchResult(
    val songs: List<McpSong>,
    val artists: List<McpArtistRef>,
    val albums: List<McpAlbumRef>,
)
