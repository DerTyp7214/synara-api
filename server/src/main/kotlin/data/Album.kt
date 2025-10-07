package dev.dertyp.data

import dev.dertyp.serializers.LocalDateTimeSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.*

@Serializable
data class Album(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val name: String,
    val artists: List<Artist>,
    val coverUrl: String,
    val songCount: Int = 0,
    @Serializable(with = LocalDateTimeSerializer::class)
    val releaseDate: LocalDateTime,
    val totalDuration: Long,
)
