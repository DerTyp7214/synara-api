package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumCreateOperationPayloadDataRelationshipsArtists(
    val data: List<AlbumCreateOperationPayloadDataRelationshipsArtistsData>
)