package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TracksSingleRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: ResourceIdentifier? = null,
    val included: List<IncludedInner<A, R>>
)