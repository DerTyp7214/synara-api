package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistCreateOperationPayload(
    val data: PlaylistCreateOperationPayloadData
)