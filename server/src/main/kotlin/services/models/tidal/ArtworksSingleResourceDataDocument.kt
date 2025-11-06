package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworksSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: ArtworksResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)