package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsFollowersMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<ArtistsFollowersResourceIdentifier>? = emptyList(),
    val included: List<IncludedInner<A, R>>? = emptyList(),
)