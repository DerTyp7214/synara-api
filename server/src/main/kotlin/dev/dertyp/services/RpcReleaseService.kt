package dev.dertyp.services

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.services.models.FollowedArtist
import dev.dertyp.services.models.RecentRelease
import java.util.UUID

class RpcReleaseService(
    private val user: User?,
    private val releaseService: ReleaseService
) : IReleaseService {
    private fun requireUser(): User = user ?: throw UnauthorizedException("No user found")

    override suspend fun followArtist(musicBrainzId: UUID): Boolean {
        return releaseService.followArtist(requireUser().id, musicBrainzId, HttpClientPriority.HIGH)
    }

    override suspend fun unfollowArtist(artistId: UUID): Boolean {
        return releaseService.unfollowArtist(requireUser().id, artistId)
    }

    override suspend fun getFollowedArtists(): List<FollowedArtist> {
        return releaseService.getFollowedArtists(requireUser().id)
    }

    override suspend fun getRecentReleases(page: Int, pageSize: Int): PaginatedResponse<RecentRelease> {
        return releaseService.getRecentReleases(requireUser().id, page, pageSize)
    }

    override suspend fun getArtistRecentReleases(artistId: UUID, page: Int, pageSize: Int): PaginatedResponse<RecentRelease> {
        return releaseService.getArtistRecentReleases(artistId, page, pageSize)
    }

    override suspend fun getRecentReleasesByMusicBrainzId(musicBrainzId: UUID, page: Int, pageSize: Int): PaginatedResponse<RecentRelease> {
        return releaseService.getRecentReleasesByMusicBrainzId(musicBrainzId, page, pageSize)
    }

    override suspend fun getReleaseImage(releaseId: UUID, size: Int): ByteArray? {
        return releaseService.getReleaseImage(releaseId, size)
    }

    override suspend fun refreshRecentRelease(releaseId: UUID) {
        releaseService.refreshRecentReleaseAsync(releaseId)
    }
}
