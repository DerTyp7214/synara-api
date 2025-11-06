package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class VideosRelationships(
    val albums: MultiRelationshipDataDocument,
    val artists: MultiRelationshipDataDocument,
    val providers: MultiRelationshipDataDocument,
    val thumbnailArt: MultiRelationshipDataDocument
): BaseRelationships()