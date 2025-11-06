package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworkCreateOperationPayloadData(
    val attributes: ArtworkCreateOperationPayloadDataAttributes,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        artworks;
    }
}

