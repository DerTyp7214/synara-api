package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistBiographiesSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: ArtistBiographiesResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)