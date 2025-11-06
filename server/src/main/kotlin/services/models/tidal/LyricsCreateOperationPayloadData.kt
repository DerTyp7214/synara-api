package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsCreateOperationPayloadData(
    val relationships: LyricsCreateOperationPayloadDataRelationships,
    val type: Type,
    val attributes: LyricsCreateOperationPayloadDataAttributes? = null
) {
    @Suppress("EnumEntryName")
    enum class Type {
        lyrics;
    }
}

