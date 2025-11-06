package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsAlbumsMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<UserCollectionsAlbumsResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)