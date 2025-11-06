package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsUpdateOperationPayload(
    val meta: ArtistClaimsUpdateOperationPayloadMeta,
    val data: ArtistClaimsUpdateOperationPayloadData? = null
)