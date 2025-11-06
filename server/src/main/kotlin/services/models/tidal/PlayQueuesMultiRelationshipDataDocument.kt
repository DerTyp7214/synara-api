package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<ResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)