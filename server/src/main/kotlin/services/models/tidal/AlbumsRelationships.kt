package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AlbumsRelationships<A : BaseAttributes, R : BaseRelationships>(
    val artists: MultiRelationshipDataDocument,
    val coverArt: MultiRelationshipDataDocument,
    val genres: MultiRelationshipDataDocument,
    val items: AlbumsItemsMultiRelationshipDataDocument<A, R>? = null,
    val owners: MultiRelationshipDataDocument,
    val providers: MultiRelationshipDataDocument,
    val similarAlbums: MultiRelationshipDataDocument
)