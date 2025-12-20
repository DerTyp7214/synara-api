package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsMultiRelationshipDataDocument<A : AttributeType, R : BaseRelationships>(
    val links: Links,
    val data: List<PlaylistsResourceObject<A, R>>,
    val included: List<IncludedInner<A, R>>? = emptyList(),
)