package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<PlaylistsResourceObject<A, R>>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)