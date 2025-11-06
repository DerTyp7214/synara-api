package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: ArtistsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)