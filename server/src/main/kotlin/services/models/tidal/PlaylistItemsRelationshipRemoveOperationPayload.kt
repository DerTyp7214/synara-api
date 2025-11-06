package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistItemsRelationshipRemoveOperationPayload(
    val data: List<PlaylistItemsRelationshipRemoveOperationPayloadData>
)