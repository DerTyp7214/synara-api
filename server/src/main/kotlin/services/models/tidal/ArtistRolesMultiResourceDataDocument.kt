package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistRolesMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<ArtistRolesResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)