package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ProvidersMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<ProvidersResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)