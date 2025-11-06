package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackManifestsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: TrackManifestsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)