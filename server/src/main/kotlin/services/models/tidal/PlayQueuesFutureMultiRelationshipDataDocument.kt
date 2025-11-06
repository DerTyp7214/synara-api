package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesFutureMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<PlayQueuesFutureResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)