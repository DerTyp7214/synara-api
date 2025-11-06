package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistItemsRelationshipReorderOperationPayload(
    val data: List<PlaylistItemsRelationshipReorderOperationPayloadData>,
    val meta: PlaylistItemsRelationshipReorderOperationPayloadMeta? = null
)