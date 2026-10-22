package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority

interface LyricsProvider {
    val id: String
    val name: String
    val url: String?
    val usuallyWordSynced: Boolean

    suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult
}

data class LyricsRequest(
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val songPath: String? = null,
)

data class TimedRow(val startMs: Long, val endMs: Long?, val text: String)

sealed interface LyricsPayload {
    data class Ttml(val xml: String) : LyricsPayload
    data class Lrc(val text: String) : LyricsPayload
    data class Plain(val text: String) : LyricsPayload
    class Krc(val bytes: ByteArray) : LyricsPayload
    data class LyricsPlusJson(val json: String) : LyricsPayload
    data class LyricsFile(val yaml: String) : LyricsPayload
    data class TimedRows(val rows: List<TimedRow>) : LyricsPayload
    data object Instrumental : LyricsPayload
}

sealed interface ProviderResult {
    data class Found(
        val payload: LyricsPayload,
        val trackTitle: String? = null,
        val trackArtist: String? = null,
        val trackAlbum: String? = null,
        val durationSec: Int? = null,
        val verifiedMetadata: Boolean = false,
    ) : ProviderResult

    data object Miss : ProviderResult

    data class Error(val cause: Throwable) : ProviderResult
}
