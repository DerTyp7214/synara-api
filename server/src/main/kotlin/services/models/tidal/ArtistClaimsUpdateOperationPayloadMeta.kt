package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsUpdateOperationPayloadMeta(
    val authorizationCode: String,
    val redirectUri: String
)