package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistCreateOperationPayload(
    val data: ArtistCreateOperationPayloadData,
    val meta: ArtistCreateOperationMeta? = null
)