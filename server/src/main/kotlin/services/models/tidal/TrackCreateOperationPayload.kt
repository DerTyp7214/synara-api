package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackCreateOperationPayload(
    val data: TrackCreateOperationPayloadData
)