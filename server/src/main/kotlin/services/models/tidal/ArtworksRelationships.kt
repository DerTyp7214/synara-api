package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworksRelationships(
    val owners: MultiRelationshipDataDocument
): BaseRelationships()