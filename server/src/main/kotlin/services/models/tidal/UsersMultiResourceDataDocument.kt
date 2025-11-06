package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UsersMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<UsersResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)