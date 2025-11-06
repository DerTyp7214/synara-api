package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultsResourceObject(
    val id: String,
    val type: String,
    val attributes: SearchResultsAttributes? = null,
    val relationships: SearchResultsRelationships? = null
)