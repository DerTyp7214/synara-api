package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsRelationships(
    val acceptedArtists: MultiRelationshipDataDocument,
    val owners: MultiRelationshipDataDocument,
    val recommendedArtists: MultiRelationshipDataDocument
): BaseRelationships()