package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistBiographiesResourceObject(
    val id: String,
    val type: String,
    val attributes: ArtistBiographiesAttributes? = null,
    val relationships: ArtistBiographiesRelationships? = null
)