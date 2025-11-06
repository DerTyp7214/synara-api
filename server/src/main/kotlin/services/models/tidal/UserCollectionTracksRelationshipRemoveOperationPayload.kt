package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionTracksRelationshipRemoveOperationPayload(
    val data: List<UserCollectionTracksRelationshipRemoveOperationPayloadData>
)