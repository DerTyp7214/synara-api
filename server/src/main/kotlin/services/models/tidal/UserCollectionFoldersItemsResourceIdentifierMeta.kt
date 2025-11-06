package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class UserCollectionFoldersItemsResourceIdentifierMeta(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val addedAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastModifiedAt: OffsetDateTime
)