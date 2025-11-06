package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsPlaylistsResourceIdentifier(
    val id: String,
    val type: String,
    val meta: UserCollectionsPlaylistsResourceIdentifierMeta? = null
)