package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionVideosRelationshipAddOperationPayload(
    val data: List<UserCollectionVideosRelationshipAddOperationPayloadData>
)