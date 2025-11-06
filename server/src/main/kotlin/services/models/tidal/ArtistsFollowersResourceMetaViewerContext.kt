package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistsFollowersResourceMetaViewerContext(
    val followsMyArtist: Boolean? = false,
    val myArtistFollows: Boolean? = false
)