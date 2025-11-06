package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsVideosResourceIdentifier(
    val id: String,
    val type: String,
    val meta: UserCollectionsVideosResourceIdentifierMeta? = null
)