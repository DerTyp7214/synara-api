package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class TrackStatisticsAttributes(
    val totalPlaybacks: Int,
    val uniqueListeners: Int
): BaseAttributes()