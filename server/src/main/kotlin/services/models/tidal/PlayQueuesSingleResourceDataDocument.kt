package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: PlayQueuesResourceObject<A, R>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)