package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchSuggestionsRelationships(
    val directHits: MultiRelationshipDataDocument
): BaseRelationships()