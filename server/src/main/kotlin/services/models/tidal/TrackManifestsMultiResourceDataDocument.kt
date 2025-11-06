package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackManifestsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<TrackManifestsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)