package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumsItemsMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<AlbumsItemsResourceIdentifier>,
    val included: List<IncludedInner<A, R>>
)