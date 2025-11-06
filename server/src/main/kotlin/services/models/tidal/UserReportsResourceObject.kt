package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserReportsResourceObject(
    val id: String,
    val type: String,
    val attributes: UserReportsAttributes? = null
)