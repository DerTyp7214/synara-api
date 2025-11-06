package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackCreateOperationPayloadData(
    val attributes: TrackCreateOperationPayloadDataAttributes,
    val relationships: TrackCreateOperationPayloadDataRelationships,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        tracks;
    }
}

