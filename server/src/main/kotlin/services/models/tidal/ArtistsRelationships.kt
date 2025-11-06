package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsRelationships(
    val albums: MultiRelationshipDataDocument,
    val biography: SingleRelationshipDataDocument,
    val followers: ArtistsFollowersMultiRelationshipDataDocument<ArtistsAttributes, ArtistsRelationships>,
    val following: ArtistsFollowingMultiRelationshipDataDocument<ArtistsAttributes, ArtistsRelationships>,
    val owners: MultiRelationshipDataDocument,
    val profileArt: MultiRelationshipDataDocument,
    val radio: MultiRelationshipDataDocument,
    val roles: MultiRelationshipDataDocument,
    val similarArtists: MultiRelationshipDataDocument,
    val trackProviders: ArtistsTrackProvidersMultiRelationshipDataDocument<ArtistsAttributes, ArtistsRelationships>,
    val tracks: MultiRelationshipDataDocument,
    val videos: MultiRelationshipDataDocument
): BaseRelationships()