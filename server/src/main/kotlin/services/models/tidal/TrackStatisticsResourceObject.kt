package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackStatisticsResourceObject(
    val id: String,
    val type: String,
    val attributes: TrackStatisticsAttributes? = null,
    val relationships: TrackStatisticsRelationships? = null
)