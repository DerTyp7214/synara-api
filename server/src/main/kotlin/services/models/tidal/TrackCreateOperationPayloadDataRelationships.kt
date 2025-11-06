package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackCreateOperationPayloadDataRelationships(
    val albums: TrackCreateOperationPayloadDataRelationshipsAlbums,
    val artists: TrackCreateOperationPayloadDataRelationshipsArtists,
    val genres: TrackCreateOperationPayloadDataRelationshipsGenres? = null
): BaseRelationships()