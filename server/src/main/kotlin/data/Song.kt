package dev.dertyp.data

import dev.dertyp.serializers.LocalDateTimeSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.*

@Serializable
data class Song(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val title: String,
    val artists: List<Artist>,
    val album: Album,
    val coverUrl: String,
    val duration: Long,
    @Serializable(with = LocalDateTimeSerializer::class)
    val releaseDate: LocalDateTime,
    val lyrics: String = "",
    val path: String,
)
