package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistProfileArtRelationshipUpdateOperationPayload(
    val data: List<ArtistProfileArtRelationshipUpdateOperationPayloadData>
)