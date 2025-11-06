package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistUpdateBody(
    val data: ArtistUpdateBodyData,
    val meta: ArtistUpdateBodyMeta? = null
)