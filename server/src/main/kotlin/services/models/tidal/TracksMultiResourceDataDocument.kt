package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TracksMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<TracksResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)