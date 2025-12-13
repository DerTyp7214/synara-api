package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsFollowingMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<ArtistsFollowingResourceIdentifier>? = emptyList(),
    val included: List<IncludedInner<A, R>>? = emptyList()
)