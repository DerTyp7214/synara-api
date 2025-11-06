package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackSourceFilesMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<TrackSourceFilesResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)