package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SharesCreateOperationPayload(
    val data: SharesCreateOperationPayloadData
)