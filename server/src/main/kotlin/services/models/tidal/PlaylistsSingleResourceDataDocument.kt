package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsSingleResourceDataDocument<A : AttributeType, R : BaseRelationships>(
    val data: PlaylistsResourceObject<A, R>,
    val links: Links,
    val included: List<IncludedInner<A, R>>? = emptyList()
)