package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsTrackProvidersMultiRelationshipDataDocument<A : BaseAttributes, R : BaseRelationships>(
    val links: Links,
    val data: List<ArtistsTrackProvidersResourceIdentifier>? = emptyList(),
    val included: List<IncludedInner<A, R>>? = emptyList()
)