package dev.dertyp.services

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.services.models.FollowedArtist
import dev.dertyp.services.models.RecentRelease
import java.util.UUID

class RpcReleaseService(
    private val user: User,
    private val releaseService: ReleaseService
) : IReleaseService {
    override suspend fun followArtist(musicBrainzId: UUID): Boolean {
        return releaseService.followArtist(user.id, musicBrainzId)
    }

    override suspend fun unfollowArtist(artistId: UUID): Boolean {
        return releaseService.unfollowArtist(user.id, artistId)
    }

    override suspend fun getFollowedArtists(): List<FollowedArtist> {
        return releaseService.getFollowedArtists(user.id)
    }

    override suspend fun getRecentReleases(page: Int, pageSize: Int): PaginatedResponse<RecentRelease> {
        return releaseService.getRecentReleases(user.id, page, pageSize)
    }
}
