package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateCurrentOperationsPayload(
    val data: PlayQueueUpdateCurrentOperationsPayloadData
)