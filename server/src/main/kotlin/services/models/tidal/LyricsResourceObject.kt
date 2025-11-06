package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsResourceObject(
    val id: String,
    val type: String,
    val attributes: LyricsAttributes? = null,
    val relationships: LyricsRelationships? = null
)