package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: ArtistClaimsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)