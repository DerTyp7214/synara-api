package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistItemsRelationshipReorderOperationPayloadData(
    val id: String,
    val meta: PlaylistItemsRelationshipReorderOperationPayloadDataMeta,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        tracks,
        videos;
    }
}

