package dev.dertyp.data

import dev.dertyp.serializers.LocalDateSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.*

@Serializable
data class Album(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val name: String,
    val artists: List<Artist>,
    val songCount: Int = 0,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate?,
    val totalDuration: Long,
)

@Serializable
data class InsertableAlbum(
    val name: String,
    val artists: List<String>,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate? = null,
    val songCount: Int = 0,
    val coverHash: String? = null,
)