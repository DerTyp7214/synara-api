package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionVideosRelationshipAddOperationPayloadData(
    val id: String,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        albums,
        appreciations,
        artistClaims,
        artistRoles,
        artists,
        artworks,
        playlists,
        providers,
        searchResults,
        searchSuggestions,
        tracks,
        trackStatistics,
        userCollections,
        userEntitlements,
        userRecommendations,
        userReports,
        users,
        videos;
    }
}

