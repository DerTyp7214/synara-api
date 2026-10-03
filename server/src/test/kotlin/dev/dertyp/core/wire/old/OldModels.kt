@file:UseContextualSerialization(Artist::class, ArtistCredit::class, Album::class, Genre::class, UUID::class)
@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire.old

import dev.dertyp.data.AudioInfo
import dev.dertyp.data.Genre
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TitleTag
import dev.dertyp.serializers.DateSerializer
import dev.dertyp.serializers.LocalDateSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.LocalDate
import java.util.Date
import java.util.UUID

@Serializable
data class Artist(
    val id: UUID,
    val name: String,
    val isGroup: Boolean,
    val artists: List<Artist> = listOf(),
    val about: String = "",
    val genres: List<Genre> = listOf(),
    val imageId: UUID? = null,
    val blurHash: String? = null,
    val musicbrainzId: UUID? = null,
    val isFollowed: Boolean = false,
    val creditedName: String? = null,
    val joinPhrase: String? = null,
)

@Serializable
data class ArtistCredit(
    val id: UUID,
    val name: String,
    val isGroup: Boolean,
    val artists: List<ArtistCredit> = listOf(),
    val genres: List<Genre> = listOf(),
    val imageId: UUID? = null,
    val blurHash: String? = null,
    val musicbrainzId: UUID? = null,
    val isFollowed: Boolean = false,
    val creditedName: String? = null,
    val joinPhrase: String? = null,
)

@Serializable
data class Album(
    val id: UUID,
    val name: String,
    val artists: List<ArtistCredit>,
    val songCount: Int = 0,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate?,
    val totalDuration: Long,
    val totalSize: Long = 0,
    val coverId: UUID? = null,
    val blurHash: String? = null,
    val genres: List<Genre> = listOf(),
    val originalId: String? = null,
    val barcode: String? = null,
    val musicbrainzId: UUID? = null,
    val animatedCoverId: UUID? = null,
    val animatedCoverImageId: UUID? = null,
    val animatedCoverBlurHash: String? = null,
)

@Serializable
data class UserSong(
    val id: UUID,
    val title: String,
    val artists: List<ArtistCredit>,
    val album: Album?,
    val duration: Long,
    val explicit: Boolean,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate? = null,
    val lyrics: String = "",
    val path: String,
    val originalUrl: String = "",
    val trackNumber: Int = 1,
    val discNumber: Int = 1,
    val copyright: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val audio: AudioInfo? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val atmos: AudioInfo? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sampleRate: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bitsPerSample: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bitRate: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fileSize: Long? = null,
    val coverId: UUID? = null,
    val blurHash: String? = null,
    val musicBrainzId: UUID? = null,
    val isrc: String? = null,
    val genres: List<Genre> = listOf(),
    val animatedCoverId: UUID? = null,
    val animatedCoverImageId: UUID? = null,
    val animatedCoverBlurHash: String? = null,
    val audioStartMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val atmosPath: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tags: List<TitleTag> = emptyList(),
    val isFavourite: Boolean? = false,
    @Serializable(with = DateSerializer::class)
    val userSongCreatedAt: Date? = null,
    @Serializable(with = DateSerializer::class)
    val userSongUpdatedAt: Date? = null,
    val likeLevel: LikeLevel? = LikeLevel.NONE,
    @Serializable(with = DateSerializer::class)
    val superLikedAt: Date? = null,
    val playbackTags: List<TimecodeTag> = emptyList(),
)

@Serializable
data class PlaybackState(
    val queue: List<QueueEntry>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val shuffleMode: Boolean,
    val repeatMode: RepeatMode,
    val sourceId: String? = null,
) {
    @Serializable
    sealed class QueueEntry {
        abstract val queueId: Long

        @Serializable
        @SerialName("FromSource")
        data class FromSource(val songId: UUID, override val queueId: Long) : QueueEntry()

        @Serializable
        @SerialName("Explicit")
        data class Explicit(val song: UserSong, override val queueId: Long) : QueueEntry()
    }
}

@Serializable
data class PlaybackReport(
    val songId: UUID,
    val positionMs: Long = 0,
    val playing: Boolean = true,
    val sentAt: Long? = null,
)

@Serializable
data class QueueInfo(
    val version: Long,
    val modifiedAt: Long,
    val modifiedBySessionId: UUID? = null,
    val modifiedByDeviceName: String? = null,
    val currentIndex: Int,
    val shuffleMode: Boolean,
    val repeatMode: RepeatMode,
    val sourceId: String? = null,
    val total: Int,
)

@Serializable
data class QueueItem(
    val songId: UUID,
    val queueId: Long,
    val position: Int,
    val shuffledPosition: Int? = null,
    val explicit: Boolean = false,
    val song: UserSong? = null,
)

@Serializable
sealed class QueueWriteResult {
    abstract val info: QueueInfo

    @Serializable
    @SerialName("Ok")
    data class Ok(override val info: QueueInfo) : QueueWriteResult()

    @Serializable
    @SerialName("Conflict")
    data class Conflict(override val info: QueueInfo) : QueueWriteResult()
}

@Serializable
data class CollectionSongMatch(
    val song: UserSong,
    val explicitMember: Boolean,
)
