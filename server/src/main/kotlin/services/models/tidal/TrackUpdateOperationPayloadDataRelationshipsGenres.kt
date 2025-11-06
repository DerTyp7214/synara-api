package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackUpdateOperationPayloadDataRelationshipsGenres(
    val data: List<TrackUpdateOperationPayloadDataRelationshipsGenresData>
)