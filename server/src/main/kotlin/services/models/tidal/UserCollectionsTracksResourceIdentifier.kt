package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsTracksResourceIdentifier(
    val id: String,
    val type: String,
    val meta: UserCollectionsTracksResourceIdentifierMeta? = null
)