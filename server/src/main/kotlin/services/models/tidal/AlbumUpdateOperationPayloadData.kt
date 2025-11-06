package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumUpdateOperationPayloadData(
    val id: String,
    val type: Type,
    val attributes: AlbumUpdateOperationPayloadDataAttributes? = null,
    val relationships: AlbumUpdateOperationPayloadDataRelationships? = null
) {
    @Suppress("EnumEntryName")
    enum class Type {
        albums;
    }
}

