package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistCreateOperationPayloadData(
    val attributes: ArtistCreateOperationPayloadDataAttributes,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        artists;
    }
}

