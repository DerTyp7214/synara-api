package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UsersSingleResourceDataDocument(
    val data: UsersResourceObject,
    val links: Links,
)