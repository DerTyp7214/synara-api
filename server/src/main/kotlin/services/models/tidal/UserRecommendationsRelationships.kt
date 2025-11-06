package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserRecommendationsRelationships(
    val discoveryMixes: MultiRelationshipDataDocument,
    val myMixes: MultiRelationshipDataDocument,
    val newArrivalMixes: MultiRelationshipDataDocument
): BaseRelationships()