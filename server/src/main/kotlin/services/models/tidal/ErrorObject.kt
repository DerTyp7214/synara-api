package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ErrorObject(
    val code: String? = null,
    val detail: String? = null,
    val id: String? = null,
    val source: ErrorObjectSource? = null,
    val status: String? = null
)