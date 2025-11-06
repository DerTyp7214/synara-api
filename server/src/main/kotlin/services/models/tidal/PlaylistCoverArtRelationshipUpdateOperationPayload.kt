package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistCoverArtRelationshipUpdateOperationPayload(
    val data: List<PlaylistCoverArtRelationshipUpdateOperationPayloadData>
)