package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SingleRelationshipDataDocument(
    val links: Links,
    val data: ResourceIdentifier? = null
)