package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class RemovePayload(
    val data: List<Data>? = null
)