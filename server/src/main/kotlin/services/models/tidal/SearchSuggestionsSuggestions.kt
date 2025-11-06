package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsSuggestions(
    val query: String,
    val highlights: List<SearchSuggestionsHighlights>? = null
)