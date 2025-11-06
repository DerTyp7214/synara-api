package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueCreateOperationPayload(
    val data: PlayQueueCreateOperationPayloadData
)