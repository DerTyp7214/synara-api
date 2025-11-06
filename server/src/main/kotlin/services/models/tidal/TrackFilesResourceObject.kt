package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackFilesResourceObject(
    val id: String,
    val type: String,
    val attributes: TrackFilesAttributes? = null
)