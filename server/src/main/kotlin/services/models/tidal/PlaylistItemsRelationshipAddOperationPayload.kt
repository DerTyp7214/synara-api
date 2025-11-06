package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistItemsRelationshipAddOperationPayload(
    val data: List<PlaylistItemsRelationshipAddOperationPayloadData>,
    val meta: PlaylistItemsRelationshipAddOperationPayloadMeta? = null
)