package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsFollowersResourceIdentifierMeta(
    val viewer: ArtistsFollowersResourceMetaViewerContext? = null
)