package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumsMultiRelationshipDataDocument<A : AttributeType, R : BaseRelationships>(
    val links: Links,
    val data: List<AlbumsResourceObject>,
    val included: List<IncludedInner<A, R>>? = emptyList(),
)