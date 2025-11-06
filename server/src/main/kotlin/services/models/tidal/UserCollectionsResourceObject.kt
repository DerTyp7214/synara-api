package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsResourceObject<A : BaseAttributes, R : BaseRelationships>(
    val id: String,
    val type: String,
    val relationships: UserCollectionsRelationships<A, R>? = null
)