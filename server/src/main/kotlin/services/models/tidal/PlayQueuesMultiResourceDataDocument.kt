package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<PlayQueuesResourceObject<A, R>>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)