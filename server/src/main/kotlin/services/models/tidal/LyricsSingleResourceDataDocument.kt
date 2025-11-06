package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsSingleResourceDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val data: LyricsResourceObject,
    val links: Links,
    val included: List<IncludedInner<A, R>>
)