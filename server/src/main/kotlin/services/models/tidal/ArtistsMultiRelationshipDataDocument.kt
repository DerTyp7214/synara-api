package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsMultiRelationshipDataDocument<A : AttributeType, R : BaseRelationships>(
    val links: Links,
    val data: List<ArtistsResourceObject>,
    val included: List<IncludedInner<A, R>>? = emptyList()
)