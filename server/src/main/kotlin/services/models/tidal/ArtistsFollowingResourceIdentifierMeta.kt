package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsFollowingResourceIdentifierMeta(
    val viewer: ArtistsFollowersResourceMetaViewerContext? = null
)