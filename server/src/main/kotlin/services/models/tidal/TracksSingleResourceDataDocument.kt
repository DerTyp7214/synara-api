package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TracksSingleResourceDataDocument<A : AttributeType, R : BaseRelationships>(
    val data: TracksResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>? = emptyList()
)