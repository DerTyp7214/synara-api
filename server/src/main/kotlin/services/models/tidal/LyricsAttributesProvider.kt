package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsAttributesProvider(
    val name: String,
    val commonTrackId: String,
    val lyricsId: String,
    val source: Source? = null
) {
    @Suppress("unused")
    enum class Source {
        TIDAL,
        THIRD_PARTY;
    }
}

