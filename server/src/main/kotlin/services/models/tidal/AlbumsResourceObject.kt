package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumsResourceObject(
    val id: String,
    val type: String,
    val attributes: AlbumsAttributes? = null,
    val relationships: AlbumsRelationships<BaseAttributes, BaseRelationships>? = null
)