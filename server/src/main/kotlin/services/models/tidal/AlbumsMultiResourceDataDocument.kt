package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<AlbumsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)