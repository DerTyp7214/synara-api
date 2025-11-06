package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsTrackProvidersResourceIdentifier(
    val id: String,
    val type: String,
    val meta: ArtistsTrackProvidersResourceIdentifierMeta? = null
)