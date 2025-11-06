package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsPlaylistsMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<UserCollectionsPlaylistsResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)