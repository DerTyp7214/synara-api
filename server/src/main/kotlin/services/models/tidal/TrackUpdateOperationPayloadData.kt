package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackUpdateOperationPayloadData(
    val id: String,
    val type: Type,
    val attributes: TrackUpdateOperationPayloadDataAttributes? = null,
    val relationships: TrackUpdateOperationPayloadDataRelationships? = null
) {
    @Suppress("EnumEntryName")
    enum class Type {
        tracks;
    }
}

