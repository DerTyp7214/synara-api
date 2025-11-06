package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumUpdateOperationPayloadDataRelationships(
    val genres: AlbumUpdateOperationPayloadDataRelationshipsGenres? = null
): BaseRelationships()