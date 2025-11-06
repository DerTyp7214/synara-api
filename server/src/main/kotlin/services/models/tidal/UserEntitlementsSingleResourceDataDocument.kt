package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserEntitlementsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: UserEntitlementsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)