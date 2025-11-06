package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AppreciationsCreateOperationPayloadData(
    val relationships: AppreciationsCreateOperationPayloadDataRelationships,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        appreciations;
    }
}

