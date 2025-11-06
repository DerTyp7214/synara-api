package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistsRelationships<A : BaseAttributes, R : BaseRelationships>(
    val coverArt: MultiRelationshipDataDocument,
    val items: PlaylistsItemsMultiRelationshipDataDocument<A, R>,
    val owners: MultiRelationshipDataDocument
)