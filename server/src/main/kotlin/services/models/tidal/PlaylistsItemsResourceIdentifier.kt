package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsItemsResourceIdentifier(
    val id: String,
    val type: String,
    val meta: PlaylistsItemsResourceIdentifierMeta? = null
)