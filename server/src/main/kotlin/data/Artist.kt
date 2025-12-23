package dev.dertyp.data

import dev.dertyp.serializers.UUIDListSerializer
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
    val about: String = "",
    @Serializable(with = UUIDSerializer::class)
    val imageId: UUID? = null,
)


@Serializable
data class MergeArtists(
    val name: String,
    val image: String? = null,
    @Serializable(with = UUIDListSerializer::class)
    val artistIds: List<UUID>,
)