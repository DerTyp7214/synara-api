package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ThirdParty(
    override val name: String,
    override val source: Source? = null,
    val commonTrackId: String,
    val lyricsId: String,
) : LyricsProvider()