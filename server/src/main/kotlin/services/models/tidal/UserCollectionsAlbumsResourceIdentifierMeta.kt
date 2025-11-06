package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class UserCollectionsAlbumsResourceIdentifierMeta(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val addedAt: OffsetDateTime
)