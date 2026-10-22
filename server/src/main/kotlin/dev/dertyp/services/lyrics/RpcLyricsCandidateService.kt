package dev.dertyp.services.lyrics

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.SongLyricsInfo
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.services.ILyricsCandidateService
import dev.dertyp.services.models.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcLyricsCandidateService(
    private val user: User,
    private val service: LyricsCandidateService
) : ILyricsCandidateService {
    override suspend fun getLyrics(songId: UUID, candidateId: UUID?): LyricsCandidate? =
        service.getLyrics(songId, candidateId)

    override suspend fun getLyricsInfo(songIds: List<UUID>): Map<UUID, SongLyricsInfo> =
        service.lyricsInfoFor(songIds)

    override suspend fun findLyrics(songId: UUID, providers: List<String>?, refresh: Boolean): LyricsSearchResult {
        if (refresh && !user.hasCapability(UserCapability.EDIT)) {
            throw UnauthorizedException("User does not have required capability: ${UserCapability.EDIT}")
        }
        return service.findLyrics(songId, providers, refresh)
    }

    override suspend fun findLyricsByQuery(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        providers: List<String>?
    ): LyricsSearchResult = service.findLyricsByQuery(title, artist, album, durationMs, providers)

    override suspend fun getLyricsCandidates(songId: UUID, includeContent: Boolean): LyricsSearchResult =
        service.getLyricsCandidates(songId, includeContent)

    override suspend fun getLyricsCandidate(candidateId: UUID): LyricsCandidate? =
        service.getLyricsCandidate(candidateId)

    override suspend fun setSyncedLyrics(songId: UUID, lyrics: SyncedLyrics?): LyricsSearchResult =
        service.setSyncedLyrics(songId, lyrics)

    override suspend fun importSyncedLyrics(songId: UUID, file: LyricsImport): LyricsSearchResult =
        service.importSyncedLyrics(songId, file)

    override suspend fun setPreferredLyrics(songId: UUID, candidateId: UUID?): LyricsSearchResult =
        service.setPreferredLyrics(songId, candidateId)

    override suspend fun getLyricsProviders(): List<LyricsProviderInfo> = service.getLyricsProviders()

    override fun streamLyrics(songId: UUID, providers: List<String>?): Flow<LyricsCandidate> =
        service.streamLyrics(songId, providers)
}
