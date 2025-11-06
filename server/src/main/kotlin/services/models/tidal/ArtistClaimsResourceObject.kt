package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsResourceObject(
    val id: String,
    val type: String,
    val attributes: ArtistClaimsAttributes? = null,
    val relationships: ArtistClaimsRelationships? = null
)