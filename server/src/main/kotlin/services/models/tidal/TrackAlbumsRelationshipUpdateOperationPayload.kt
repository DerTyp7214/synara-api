package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackAlbumsRelationshipUpdateOperationPayload(
    val data: List<TrackAlbumsRelationshipUpdateOperationPayloadData>
)