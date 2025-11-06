package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class VideosSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: VideosResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)