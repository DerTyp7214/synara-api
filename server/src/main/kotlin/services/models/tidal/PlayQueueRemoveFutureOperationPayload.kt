package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueRemoveFutureOperationPayload(
    val data: List<PlayQueueRemoveFutureOperationPayloadData>
)