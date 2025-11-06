package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class GenresSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: GenresResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)