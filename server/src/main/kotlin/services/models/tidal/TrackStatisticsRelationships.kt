package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackStatisticsRelationships(
    val owners: MultiRelationshipDataDocument
): BaseRelationships()