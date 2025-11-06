package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AppreciationsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: AppreciationsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)