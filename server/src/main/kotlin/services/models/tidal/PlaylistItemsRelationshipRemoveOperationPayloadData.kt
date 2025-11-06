package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistItemsRelationshipRemoveOperationPayloadData(
    val id: String,
    val meta: PlaylistItemsRelationshipRemoveOperationPayloadDataMeta,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        tracks,
        videos;
    }
}

