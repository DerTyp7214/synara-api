package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsResourceObject(
    val id: String,
    val type: String,
    val attributes: SearchSuggestionsAttributes? = null,
    val relationships: SearchSuggestionsRelationships? = null
)