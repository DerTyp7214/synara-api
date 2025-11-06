package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumCreateOperationPayloadData(
    val attributes: AlbumCreateOperationPayloadDataAttributes,
    val relationships: AlbumCreateOperationPayloadDataRelationships,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        albums;
    }
}

