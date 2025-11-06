package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumsItemsResourceIdentifier(
    val id: String,
    val type: String,
    val meta: AlbumsItemsResourceIdentifierMeta? = null
)