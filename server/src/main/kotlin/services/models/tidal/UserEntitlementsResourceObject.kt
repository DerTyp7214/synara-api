package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserEntitlementsResourceObject(
    val id: String,
    val type: String,
    val attributes: UserEntitlementsAttributes? = null
)