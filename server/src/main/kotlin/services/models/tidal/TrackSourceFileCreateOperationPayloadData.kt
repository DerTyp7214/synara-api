package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackSourceFileCreateOperationPayloadData(
    val attributes: TrackSourceFileCreateOperationPayloadDataAttributes,
    val relationships: TrackSourceFileCreateOperationPayloadDataRelationships,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        trackSourceFiles;
    }
}

