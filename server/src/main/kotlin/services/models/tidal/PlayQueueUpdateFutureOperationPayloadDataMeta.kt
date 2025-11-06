package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateFutureOperationPayloadDataMeta(
    val itemId: String
)