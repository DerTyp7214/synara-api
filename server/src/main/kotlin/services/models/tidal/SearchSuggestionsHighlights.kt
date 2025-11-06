package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsHighlights(
    val length: Int,
    val start: Int
)