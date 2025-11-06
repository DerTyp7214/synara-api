package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumCreateOperationPayloadDataRelationships(
    val artists: AlbumCreateOperationPayloadDataRelationshipsArtists,
    val genres: AlbumCreateOperationPayloadDataRelationshipsGenres? = null
): BaseRelationships()