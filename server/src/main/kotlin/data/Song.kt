package dev.dertyp.data

import dev.dertyp.core.contentEquals
import dev.dertyp.serializers.LocalDateSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.*

@Serializable
data class SongWithoutLyrics(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val title: String,
    val artists: List<Artist>,
    val album: Album?,
    val duration: Long,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate? = null,
    val path: String,
    val originalUrl: String = "",
    val trackNumber: Int = 1,
    val discNumber: Int = 1,
    val copyright: String = "",
    val sampleRate: Int = 0,
    val bitsPerSample: Int = 0,
    val bitRate: Long = 0,
    val fileSize: Long = 0,
    @Serializable(with = UUIDSerializer::class)
    val coverId: UUID? = null,
)

@Serializable
data class Song(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val title: String,
    val artists: List<Artist>,
    val album: Album?,
    val duration: Long,
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
    @Serializable(with = UUIDSerializer::class)
    val coverId: UUID? = null,
)

@Serializable
data class SimpleSong(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val title: String,
    val duration: Long,
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