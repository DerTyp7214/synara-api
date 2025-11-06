package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserEntitlementsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<UserEntitlementsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)