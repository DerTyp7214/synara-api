package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ErrorObjectSource(
    val header: String? = null,
    val parameter: String? = null,
    val pointer: String? = null
)