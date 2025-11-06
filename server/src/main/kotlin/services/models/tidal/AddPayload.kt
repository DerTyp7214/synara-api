package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AddPayload(
    val data: List<Data>? = null
)
