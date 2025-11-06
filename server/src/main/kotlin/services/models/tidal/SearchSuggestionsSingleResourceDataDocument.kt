package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: SearchSuggestionsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)