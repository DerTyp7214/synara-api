package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class MultiRelationshipDataDocument(
    val links: Links,
    val data: List<ResourceIdentifier>? = null
)