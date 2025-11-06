package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesResourceObject<A : BaseAttributes, R : BaseRelationships>(
    val id: String,
    val type: String,
    val attributes: PlayQueuesAttributes? = null,
    val relationships: PlayQueuesRelationships<A, R>? = null
)