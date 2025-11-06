package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TracksRelationships(
    val albums: MultiRelationshipDataDocument,
    val artists: MultiRelationshipDataDocument,
    val genres: MultiRelationshipDataDocument,
    val lyrics: MultiRelationshipDataDocument,
    val owners: MultiRelationshipDataDocument,
    val providers: MultiRelationshipDataDocument,
    val radio: MultiRelationshipDataDocument,
    val shares: MultiRelationshipDataDocument,
    val similarTracks: MultiRelationshipDataDocument,
    val sourceFile: SingleRelationshipDataDocument,
    val trackStatistics: SingleRelationshipDataDocument
): BaseRelationships()