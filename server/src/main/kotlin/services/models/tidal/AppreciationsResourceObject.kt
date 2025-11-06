package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AppreciationsResourceObject(
    val id: String,
    val type: String,
    val attributes: AppreciationsAttributes? = null
)