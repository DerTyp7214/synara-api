package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<SearchSuggestionsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)