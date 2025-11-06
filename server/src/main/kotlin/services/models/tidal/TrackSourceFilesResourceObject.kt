package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackSourceFilesResourceObject(
    val id: String,
    val type: String,
    val attributes: TrackSourceFilesAttributes? = null,
    val relationships: TrackSourceFilesRelationships? = null
)