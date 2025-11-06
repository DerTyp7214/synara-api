package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistBiographiesMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<ArtistBiographiesResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)