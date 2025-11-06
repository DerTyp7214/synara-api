package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class Data(
    val id: String,
    val resourceType: String
)

