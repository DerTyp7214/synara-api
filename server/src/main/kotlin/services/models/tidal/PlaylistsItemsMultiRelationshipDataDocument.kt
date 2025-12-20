package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsItemsMultiRelationshipDataDocument<A : AttributeType, R : BaseRelationships>(
    val links: Links,
    val data: List<PlaylistsItemsResourceIdentifier>? = null,
    val included: List<IncludedInner<A, R>>? = emptyList(),
)