package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsUpdateOperationPayload(
    val data: LyricsUpdateOperationPayloadData
)