package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumCreateOperationPayload(
    val data: AlbumCreateOperationPayloadData
)