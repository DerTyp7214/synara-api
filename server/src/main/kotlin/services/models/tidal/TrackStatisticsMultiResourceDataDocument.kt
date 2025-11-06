package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackStatisticsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<TrackStatisticsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)