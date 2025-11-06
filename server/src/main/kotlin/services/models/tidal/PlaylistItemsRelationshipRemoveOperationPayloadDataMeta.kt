package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class PlaylistItemsRelationshipRemoveOperationPayloadDataMeta(
    val itemId: String
)