package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionFoldersResourceObject<A : BaseAttributes, R : BaseRelationships>(
    val id: String,
    val type: String,
    val attributes: UserCollectionFoldersAttributes? = null,
    val relationships: UserCollectionFoldersRelationships<A, R>? = null
)