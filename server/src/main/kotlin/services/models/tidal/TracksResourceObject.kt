package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TracksResourceObject(
    val id: String,
    val type: String,
    val attributes: TracksAttributes? = null,
    val relationships: TracksRelationships? = null
)