package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class VideosMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<VideosResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)