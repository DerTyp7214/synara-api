package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SharesMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<SharesResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)