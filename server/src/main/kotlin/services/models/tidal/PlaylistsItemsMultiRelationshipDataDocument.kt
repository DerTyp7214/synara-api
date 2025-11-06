package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsItemsMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<PlaylistsItemsResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)