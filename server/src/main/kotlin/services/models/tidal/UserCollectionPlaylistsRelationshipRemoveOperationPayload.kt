package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionPlaylistsRelationshipRemoveOperationPayload(
    val data: List<UserCollectionPlaylistsRelationshipRemoveOperationPayloadData>
)