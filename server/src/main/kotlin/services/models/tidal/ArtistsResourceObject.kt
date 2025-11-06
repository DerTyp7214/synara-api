package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsResourceObject(
    val id: String,
    val type: String,
    val attributes: ArtistsAttributes? = null,
    val relationships: ArtistsRelationships? = null
)