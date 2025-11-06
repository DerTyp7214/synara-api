package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsFollowingResourceIdentifier(
    val id: String,
    val type: String,
    val meta: ArtistsFollowingResourceIdentifierMeta? = null
)