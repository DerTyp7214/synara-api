package dev.dertyp.services.podcast

import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastEpisodeType
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastSource
import java.security.MessageDigest
import java.util.UUID

data class PodcastShowRow(
    val id: UUID,
    val source: PodcastSource,
    val sourceKey: String,
    val feedUrl: String? = null,
    val localPath: String? = null,
    val title: String,
    val description: String = "",
    val author: String? = null,
    val language: String? = null,
    val link: String? = null,
    val imageId: UUID? = null,
    val imageUrl: String? = null,
    val explicit: Boolean = false,
    val deliveryMode: PodcastDeliveryMode = PodcastDeliveryMode.STREAM,
    val keepEpisodes: Int? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchedAt: Long? = null,
    val lastFetchError: String? = null,
    val orphanedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class PodcastEpisodeRow(
    val id: UUID,
    val showId: UUID,
    val guid: String,
    val guidKey: String,
    val title: String,
    val description: String = "",
    val link: String? = null,
    val publishedAt: Long,
    val durationMs: Long? = null,
    val enclosureUrl: String? = null,
    val enclosureType: String? = null,
    val enclosureLength: Long? = null,
    val filePath: String? = null,
    val fileSize: Long? = null,
    val format: String? = null,
    val imageId: UUID? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeType: PodcastEpisodeType = PodcastEpisodeType.FULL,
    val explicit: Boolean = false,
    val importState: PodcastImportState = PodcastImportState.NONE,
    val importAttempts: Int = 0,
    val importError: String? = null,
    val importedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class PodcastTranscriptRow(
    val id: UUID,
    val episodeId: UUID,
    val sourceKey: String,
    val url: String? = null,
    val filePath: String? = null,
    val type: String,
    val language: String? = null,
    val rel: String? = null,
    val content: String? = null,
    val fetchedAt: Long? = null,
    val fetchError: String? = null,
    val createdAt: Long
)

data class ParsedTranscript(
    val url: String,
    val type: String,
    val language: String? = null,
    val rel: String? = null
)

data class ParsedShow(
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val language: String? = null,
    val link: String? = null,
    val imageUrl: String? = null,
    val explicit: Boolean = false
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String? = null,
    val link: String? = null,
    val publishedAt: Long,
    val durationMs: Long? = null,
    val enclosureUrl: String,
    val enclosureType: String? = null,
    val enclosureLength: Long? = null,
    val imageUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeType: PodcastEpisodeType = PodcastEpisodeType.FULL,
    val explicit: Boolean = false,
    val transcripts: List<ParsedTranscript> = emptyList()
)

data class ParsedFeed(
    val show: ParsedShow,
    val episodes: List<ParsedEpisode>,
    val newFeedUrl: String? = null
)

sealed interface LocalTranscript {
    data class Sidecar(
        val filePath: String,
        val type: String,
        val language: String? = null
    ) : LocalTranscript

    data class Embedded(
        val content: String,
        val type: String
    ) : LocalTranscript
}

data class LocalEpisode(
    val guid: String,
    val title: String,
    val description: String? = null,
    val publishedAt: Long,
    val durationMs: Long? = null,
    val filePath: String,
    val fileSize: Long,
    val format: String,
    val episodeNumber: Int? = null,
    val imageId: UUID? = null,
    val transcripts: List<LocalTranscript> = emptyList()
)

object PodcastKeys {
    const val EMBEDDED_TRANSCRIPT_KEY = "embedded"

    private const val HEX_DIGITS = "0123456789abcdef"

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[unsigned shr 4])
            builder.append(HEX_DIGITS[unsigned and 0x0F])
        }
        return builder.toString()
    }

    fun feedSourceKey(normalizedUrl: String): String = "FEED:" + sha256Hex(normalizedUrl)

    fun localSourceKey(localPath: String): String = "LOCAL:" + sha256Hex(localPath)

    fun guidKey(guid: String): String = sha256Hex(guid)
}
