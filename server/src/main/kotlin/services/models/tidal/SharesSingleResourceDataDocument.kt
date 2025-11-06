package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SharesSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: SharesResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)