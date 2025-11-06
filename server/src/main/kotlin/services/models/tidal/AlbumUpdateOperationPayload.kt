package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumUpdateOperationPayload(
    val data: AlbumUpdateOperationPayloadData
)