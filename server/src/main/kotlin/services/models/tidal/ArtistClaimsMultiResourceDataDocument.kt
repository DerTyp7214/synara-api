package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<ArtistClaimsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)