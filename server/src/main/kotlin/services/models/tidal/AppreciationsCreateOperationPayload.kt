package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AppreciationsCreateOperationPayload(
    val data: AppreciationsCreateOperationPayloadData,
    val meta: AppreciationsCreateOperationMeta? = null
)