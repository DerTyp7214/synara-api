package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesPastMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<PlayQueuesPastResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)