package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistRolesSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: ArtistRolesResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)