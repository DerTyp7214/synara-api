package dev.dertyp.data

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
    val album: Album,
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
    @Serializable(with = UUIDSerializer::class)
    val coverId: UUID? = null,
)

@Serializable
data class Song(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val title: String,
    val artists: List<Artist>,
    val album: Album,
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
    @Serializable(with = UUIDSerializer::class)
    val coverId: UUID? = null,
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
    val coverHash: String? = null,
)