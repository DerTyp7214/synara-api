package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsMultiResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: List<LyricsResourceObject>,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)