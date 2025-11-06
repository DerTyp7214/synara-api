package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SharesCreateOperationPayloadData(
    val relationships: SharesCreateOperationPayloadDataRelationships,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        shares;
    }
}

