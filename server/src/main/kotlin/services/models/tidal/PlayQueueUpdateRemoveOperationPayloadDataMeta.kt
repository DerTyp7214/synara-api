package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateRemoveOperationPayloadDataMeta(
    val itemId: String
)