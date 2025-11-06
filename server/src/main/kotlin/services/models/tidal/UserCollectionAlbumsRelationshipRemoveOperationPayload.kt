package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionAlbumsRelationshipRemoveOperationPayload(
    val data: List<UserCollectionAlbumsRelationshipRemoveOperationPayloadData>
)