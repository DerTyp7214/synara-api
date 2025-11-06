package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<SearchResultsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)