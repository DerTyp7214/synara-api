package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class GenresMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<GenresResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)