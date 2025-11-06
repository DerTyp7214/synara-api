package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionAlbumsRelationshipAddOperationPayload(
    val data: List<UserCollectionAlbumsRelationshipAddOperationPayloadData>
)