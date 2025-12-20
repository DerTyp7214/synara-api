package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsResourceObject<A : AttributeType, R : BaseRelationships>(
    val id: String,
    val type: String,
    val attributes: PlaylistsAttributes? = null,
    val relationships: PlaylistsRelationships<A, R>? = null
)