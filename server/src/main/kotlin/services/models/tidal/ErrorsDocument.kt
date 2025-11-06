package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ErrorsDocument(
    val errors: List<ErrorObject>? = null,
    val links: Links? = null
)