package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumUpdateOperationPayloadDataRelationshipsGenres(
    val data: List<AlbumUpdateOperationPayloadDataRelationshipsGenresData>
)