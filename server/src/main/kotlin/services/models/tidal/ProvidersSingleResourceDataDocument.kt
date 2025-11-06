package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ProvidersSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: ProvidersResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)