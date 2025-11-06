package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class VideosResourceObject(
    val id: String,
    val type: String,
    val attributes: VideosAttributes? = null,
    val relationships: VideosRelationships? = null
)