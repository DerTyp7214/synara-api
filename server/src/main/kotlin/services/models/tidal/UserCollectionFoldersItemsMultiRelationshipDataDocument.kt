package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionFoldersItemsMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<UserCollectionFoldersItemsResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)