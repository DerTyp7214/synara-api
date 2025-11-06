package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsCreateOperationPayload(
    val data: LyricsCreateOperationPayloadData,
    val meta: LyricsCreateOperationPayloadMeta? = null
)