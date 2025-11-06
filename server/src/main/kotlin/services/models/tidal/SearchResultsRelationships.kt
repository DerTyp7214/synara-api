package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultsRelationships(
    val albums: MultiRelationshipDataDocument,
    val artists: MultiRelationshipDataDocument,
    val playlists: MultiRelationshipDataDocument,
    val topHits: MultiRelationshipDataDocument,
    val tracks: MultiRelationshipDataDocument,
    val videos: MultiRelationshipDataDocument
): BaseRelationships()