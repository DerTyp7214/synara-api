package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateFutureOperationPayload(
    val data: List<PlayQueueUpdateFutureOperationPayloadData>,
    val meta: PlayQueueUpdateFutureOperationPayloadMeta
)