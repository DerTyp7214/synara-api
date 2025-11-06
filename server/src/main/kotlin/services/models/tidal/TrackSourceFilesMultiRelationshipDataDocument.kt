package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackSourceFilesMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<ResourceIdentifier>? = null,
    val included: List<IncludedInner<A, R>>
)