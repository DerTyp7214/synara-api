package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class UserCollectionFoldersAttributes(
    val collectionType: CollectionType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastModifiedAt: OffsetDateTime,
    val name: String
): BaseAttributes() {
    @Suppress("unused")
    enum class CollectionType {
        PLAYLISTS;
    }
}

