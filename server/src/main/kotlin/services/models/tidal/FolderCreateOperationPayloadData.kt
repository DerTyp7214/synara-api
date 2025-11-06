package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class FolderCreateOperationPayloadData(
    val attributes: Attributes,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        userCollectionFolders;
    }
}

