package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworksMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<ArtworksResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)