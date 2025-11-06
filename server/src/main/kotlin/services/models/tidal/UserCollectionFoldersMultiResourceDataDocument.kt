package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionFoldersMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<UserCollectionFoldersResourceObject<A, R>>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)