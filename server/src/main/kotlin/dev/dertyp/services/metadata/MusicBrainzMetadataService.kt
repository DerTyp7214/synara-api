package dev.dertyp.services.metadata

import dev.dertyp.PlatformUUID
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.getDateFromISO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.server.application.ApplicationEnvironment
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MusicBrainzMetadataService(
    private val musicBrainzService: IMusicBrainzService,
    environment: ApplicationEnvironment
) : MetadataService("MusicBrainz", IMetadataService.MetadataType.musicBrainz, environment) {

    override val clientIdConfigPath: String = ""
    override val clientSecretConfigPath: String = ""
    override val tokenUrl: String = ""

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {}
    override suspend fun getAccessToken(): IMetadataService.AccessTokenResponse = IMetadataService.AccessTokenResponse("", "", 0)

    override suspend fun getAlbumIdByTrackId(trackId: String, priority: HttpClientPriority): String? {
        val mbId = try { UUID.fromString(trackId) } catch (_: Exception) { return null }
        val recording = musicBrainzService.getRecording(mbId) ?: return null
        return recording.releases?.firstOrNull()?.id?.toString()
    }

    override suspend fun getTracksByIds(trackIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Track> {
        return trackIds.mapNotNull { id ->
            try {
                getTrackByMbId(UUID.fromString(id), priority)
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Album> {
        return albumIds.mapNotNull { id ->
            try {
                getAlbumByMbId(UUID.fromString(id), priority)
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun getArtistsByIds(artistIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Artist> {
        return artistIds.mapNotNull { id ->
            try {
                getArtistByMbId(UUID.fromString(id), priority)
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun getArtistByMbId(mbId: PlatformUUID, priority: HttpClientPriority): IMetadataService.Artist? {
        val artist = musicBrainzService.getArtist(mbId) ?: return null
        return IMetadataService.Artist(
            id = artist.id.toString(),
            name = artist.name ?: "",
            popularity = 0f,
            images = getImageUrlByArtistMbId(mbId, priority),
            genres = artist.genres?.map { it.name } ?: emptyList()
        )
    }

    override suspend fun getAlbumByMbId(mbId: PlatformUUID, priority: HttpClientPriority): IMetadataService.Album? {
        val release = musicBrainzService.getRelease(mbId) ?: return null
        return IMetadataService.Album(
            id = release.id.toString(),
            title = release.title ?: "",
            artists = release.artistCredit?.mapNotNull { it.name ?: it.artist?.name } ?: emptyList(),
            trackCount = 0,
            releaseDate = release.date?.let { getDateFromISO(it) },
            images = getImageUrlByAlbumMbId(mbId, priority)
        )
    }

    override suspend fun getTrackByMbId(mbId: PlatformUUID, priority: HttpClientPriority): IMetadataService.Track? {
        val recording = musicBrainzService.getRecording(mbId) ?: return null
        val firstRelease = recording.releases?.firstOrNull()
        return IMetadataService.Track(
            id = recording.id.toString(),
            title = recording.title ?: "",
            artists = recording.artistCredit?.mapNotNull { it.name ?: it.artist?.name } ?: emptyList(),
            duration = recording.length?.milliseconds ?: Duration.ZERO,
            images = getImageUrlByTrackMbId(mbId, priority),
            albumId = firstRelease?.id?.toString(),
            albumTitle = firstRelease?.title
        )
    }

    override suspend fun getImageUrlByAlbumMbId(mbId: PlatformUUID, priority: HttpClientPriority): List<IMetadataService.Image> {
        return listOf(IMetadataService.Image("https://coverartarchive.org/release-group/$mbId/front", 0, 0))
    }

    override suspend fun getImageUrlByTrackMbId(mbId: PlatformUUID, priority: HttpClientPriority): List<IMetadataService.Image> {
        val recording = musicBrainzService.getRecording(mbId) ?: return emptyList()
        val firstRelease = recording.releases?.firstOrNull()
        val releaseGroupId = firstRelease?.releaseGroup?.id ?: firstRelease?.id
        return if (releaseGroupId != null) {
            listOf(IMetadataService.Image("https://coverartarchive.org/release-group/$releaseGroupId/front", 0, 0))
        } else emptyList()
    }
}
