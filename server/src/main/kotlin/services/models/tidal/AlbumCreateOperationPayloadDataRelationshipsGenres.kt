package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumCreateOperationPayloadDataRelationshipsGenres(
    val data: List<AlbumCreateOperationPayloadDataRelationshipsGenresData>
)