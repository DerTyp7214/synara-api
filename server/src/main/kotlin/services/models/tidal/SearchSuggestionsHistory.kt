package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsHistory(
    val query: String,
    val highlights: List<SearchSuggestionsHighlights>? = null
)