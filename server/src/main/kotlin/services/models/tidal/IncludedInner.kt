package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class IncludedInner<A : BaseAttributes, R : BaseRelationships>(
    val id: String,
    val type: String,
    val attributes: A,
    val relationships: R,
)