package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class PlaylistsItemsResourceIdentifierMeta(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val addedAt: OffsetDateTime? = null,
    val itemId: String? = null
)