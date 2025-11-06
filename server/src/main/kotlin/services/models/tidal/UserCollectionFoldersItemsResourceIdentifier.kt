package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionFoldersItemsResourceIdentifier(
    val id: String,
    val type: String,
    val meta: UserCollectionFoldersItemsResourceIdentifierMeta? = null
)