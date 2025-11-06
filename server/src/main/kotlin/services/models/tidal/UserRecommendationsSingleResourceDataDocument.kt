package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserRecommendationsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: UserRecommendationsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)