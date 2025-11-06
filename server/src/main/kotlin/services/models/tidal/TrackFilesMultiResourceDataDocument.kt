package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackFilesMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<TrackFilesResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)