package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateOperationPayloadData(
    val attributes: PlayQueueUpdateOperationPayloadDataAttributes,
    val id: String,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        playQueues;
    }
}

