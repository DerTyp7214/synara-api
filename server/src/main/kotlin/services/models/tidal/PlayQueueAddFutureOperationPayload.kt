package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueAddFutureOperationPayload(
    val data: List<PlayQueueAddFutureOperationPayloadData>,
    val meta: PlayQueueAddFutureOperationPayloadMeta? = null
)