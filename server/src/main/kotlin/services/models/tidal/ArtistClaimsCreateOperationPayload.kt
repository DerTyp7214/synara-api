package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsCreateOperationPayload(
    val data: ArtistClaimsCreateOperationPayloadData,
    val meta: ArtistClaimsCreateOperationPayloadMeta
)