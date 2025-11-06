package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimAcceptedArtistsRelationshipUpdateOperationPayload(
    val data: List<ArtistClaimAcceptedArtistsRelationshipUpdateOperationPayloadData>
)