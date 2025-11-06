package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: AlbumsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)