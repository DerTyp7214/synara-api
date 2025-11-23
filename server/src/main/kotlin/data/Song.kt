package dev.dertyp.data

import dev.dertyp.core.contentEquals
import dev.dertyp.serializers.DateSerializer
import dev.dertyp.serializers.LocalDateSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.*

abstract class BaseSong() {
    @Serializable(with = UUIDSerializer::class)
    abstract val id: UUID
    abstract val title: String
    abstract val artists: List<Artist>
    abstract val album: Album?
    abstract val duration: Long
    abstract val explicit: Boolean
    @Serializable(with = LocalDateSerializer::class)
    abstract val releaseDate: LocalDate?
    abstract val lyrics: String
    abstract val path: String
    abstract val originalUrl: String
    abstract val trackNumber: Int
    abstract val discNumber: Int
    abstract val copyright: String
    abstract val sampleRate: Int
    abstract val bitsPerSample: Int
    abstract val bitRate: Long
    abstract val fileSize: Long
    @Serializable(with = UUIDSerializer::class)
    abstract val coverId: UUID?
}

@Serializable
data class Song(
    @Serializable(with = UUIDSerializer::class)
    override val id: UUID,
    override val title: String,
    override val artists: List<Artist>,
    override val album: Album?,
    override val duration: Long,
    override val explicit: Boolean,
    @Serializable(with = LocalDateSerializer::class)
    override val releaseDate: LocalDate? = null,
    override val lyrics: String = "",
    override val path: String,
    override val originalUrl: String = "",
    override val trackNumber: Int = 1,
    override val discNumber: Int = 1,
    override val copyright: String = "",
    override val sampleRate: Int = 0,
    override val bitsPerSample: Int = 0,
    override val bitRate: Long = 0,
    override val fileSize: Long = 0,
    @Serializable(with = UUIDSerializer::class)
    override val coverId: UUID? = null,
): BaseSong()

@Serializable
data class UserSong(
    @Serializable(with = UUIDSerializer::class)
    override val id: UUID,
    override val title: String,
    override val artists: List<Artist>,
    override val album: Album?,
    override val duration: Long,
    override val explicit: Boolean,
    @Serializable(with = LocalDateSerializer::class)
    override val releaseDate: LocalDate? = null,
    override val lyrics: String = "",
    override val path: String,
    override val originalUrl: String = "",
    override val trackNumber: Int = 1,
    override val discNumber: Int = 1,
    override val copyright: String = "",
    override val sampleRate: Int = 0,
    override val bitsPerSample: Int = 0,
    override val bitRate: Long = 0,
    override val fileSize: Long = 0,
    @Serializable(with = UUIDSerializer::class)
    override val coverId: UUID? = null,

    val isFavourite: Boolean? = false,
    @Serializable(with = DateSerializer::class)
    val userSongCreatedAt: Date? = Date.from(Instant.now()),
    @Serializable(with = DateSerializer::class)
    val userSongUpdatedAt: Date? = Date.from(Instant.now()),
): BaseSong()

@Serializable
data class SimpleSong(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val title: String,
    val duration: Long,
    val explicit: Boolean,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate?,
    val path: String,
    val originalUrl: String,
    val trackNumber: Int,
    val discNumber: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val bitRate: Long,
    val fileSize: Long,
    @Serializable(with = UUIDSerializer::class)
    val coverId: UUID?,
    val transcodedTo: List<Int>
)

@Serializable
data class InsertableSong(
    val title: String,
    val artists: List<String> = listOf(),
    val album: InsertableAlbum,
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
    val sampleRate: Int = 0,
    val bitsPerSample: Int = 0,
    val bitRate: Long = 0,
    val fileSize: Long = 0,
    val coverHash: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        return if (other is InsertableSong) contentEquals(other) else false
    }

    override fun hashCode(): Int {
        var result = trackNumber
        result = 31 * result + title.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + discNumber.hashCode()
        result = 31 * result + album.name.hashCode()
        result = 31 * result + (releaseDate?.hashCode() ?: 0)
        return result
    }
}