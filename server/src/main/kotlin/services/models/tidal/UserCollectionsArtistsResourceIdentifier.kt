package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsArtistsResourceIdentifier(
    val id: String,
    val type: String,
    val meta: UserCollectionsArtistsResourceIdentifierMeta? = null
)