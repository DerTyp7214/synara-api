package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsRelationships(
    val owners: MultiRelationshipDataDocument,
    val track: SingleRelationshipDataDocument
): BaseRelationships()