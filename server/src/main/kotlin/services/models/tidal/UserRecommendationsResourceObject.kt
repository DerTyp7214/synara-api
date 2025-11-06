package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserRecommendationsResourceObject(
    val id: String,
    val type: String,
    val relationships: UserRecommendationsRelationships? = null
)