package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserReportsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<UserReportsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)