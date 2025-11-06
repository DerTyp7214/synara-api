package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsAttributes(
    val trackingId: String,
    val history: List<SearchSuggestionsHistory>? = null,
    val suggestions: List<SearchSuggestionsSuggestions>? = null
): BaseAttributes()