package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class Links(
    val self: String,
    val meta: LinksMeta? = null,
    val next: String? = null
)