package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateFutureOperationPayloadData(
    val id: String,
    val meta: PlayQueueUpdateFutureOperationPayloadDataMeta,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        tracks,
        videos;
    }
}

