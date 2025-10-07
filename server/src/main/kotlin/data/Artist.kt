package dev.dertyp.data

import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class Artist(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val name: String,
    val isGroup: Boolean,
    val artists: List<Artist> = listOf(),
    val imageUrl: String,
    val about: String = "",
)
