package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsRelationships<A : AttributeType, R : BaseRelationships>(
    val coverArt: MultiRelationshipDataDocument,
    val items: PlaylistsItemsMultiRelationshipDataDocument<A, R>? = null,
    val owners: MultiRelationshipDataDocument
)