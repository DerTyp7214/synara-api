package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionTracksRelationshipAddOperationPayload(
    val data: List<UserCollectionTracksRelationshipAddOperationPayloadData>
)