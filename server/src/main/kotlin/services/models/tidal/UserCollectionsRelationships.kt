package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionsRelationships<A : BaseAttributes, R : BaseRelationships>(
    val albums: UserCollectionsAlbumsMultiRelationshipDataDocument<A, R>,
    val artists: UserCollectionsArtistsMultiRelationshipDataDocument<A, R>,
    val owners: MultiRelationshipDataDocument,
    val playlists: UserCollectionsPlaylistsMultiRelationshipDataDocument<A, R>,
    val tracks: UserCollectionsTracksMultiRelationshipDataDocument<A, R>,
    val videos: UserCollectionsVideosMultiRelationshipDataDocument<A, R>
)