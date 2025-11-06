package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsTracksMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<UserCollectionsTracksResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)