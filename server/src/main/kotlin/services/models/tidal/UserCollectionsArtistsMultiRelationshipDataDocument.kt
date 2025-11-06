package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsArtistsMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<UserCollectionsArtistsResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)