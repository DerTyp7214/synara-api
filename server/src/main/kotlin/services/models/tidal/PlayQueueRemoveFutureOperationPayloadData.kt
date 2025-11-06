package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueRemoveFutureOperationPayloadData(
    val id: String,
    val meta: PlayQueueUpdateRemoveOperationPayloadDataMeta,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        tracks,
        videos;
    }
}

