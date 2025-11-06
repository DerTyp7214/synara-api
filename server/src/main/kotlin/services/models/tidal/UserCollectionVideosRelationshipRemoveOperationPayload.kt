package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionVideosRelationshipRemoveOperationPayload(
    val data: List<UserCollectionVideosRelationshipRemoveOperationPayloadData>
)