package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateCurrentOperationsPayloadData(
    val id: String,
    val meta: PlayQueueUpdateCurrentOperationsPayloadDataMeta,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        tracks,
        videos;
    }
}

