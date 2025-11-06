package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsCreateOperationPayloadMeta(
    val redirectUrl: String,
    val nonce: String? = null
)