package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackSourceFilesSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: TrackSourceFilesResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)