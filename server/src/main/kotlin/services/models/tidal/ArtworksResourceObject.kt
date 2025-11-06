package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworksResourceObject(
    val id: String,
    val type: String,
    val attributes: ArtworksAttributes? = null,
    val relationships: ArtworksRelationships? = null
)