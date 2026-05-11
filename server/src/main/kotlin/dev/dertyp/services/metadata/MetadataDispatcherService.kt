package dev.dertyp.services.metadata

import dev.dertyp.PlatformUUID
import dev.dertyp.services.Service
import io.ktor.server.application.ApplicationEnvironment

class MetadataDispatcherService(
    private val environment: ApplicationEnvironment
) : IMetadataService, Service() {

    private fun getService(type: IMetadataService.MetadataType): IMetadataService {
        return MetadataService.getMetadataService(type, environment)
    }

    override suspend fun getSupportedFeatures(type: IMetadataService.MetadataType): Set<IMetadataService.Feature> =
        getService(type).getSupportedFeatures(type)

    override suspend fun getAllMetadataTypes(features: Set<IMetadataService.Feature>): List<IMetadataService.MetadataType> {
        val allTypes = IMetadataService.MetadataType.all()
        if (features.isEmpty()) return allTypes

        return allTypes.filter { type ->
            val supported = getService(type).getSupportedFeatures(type)
            features.all { it in supported }
        }
    }

    override suspend fun searchArtists(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Artist> = getService(type).searchArtists(type, query, limit)

    override suspend fun search(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Track> = getService(type).search(type, query, limit)

    override suspend fun searchAlbums(
        type: IMetadataService.MetadataType,
        query: String,
        limit: Int,
        includeTracks: Boolean
    ): List<IMetadataService.Album> = getService(type).searchAlbums(type, query, limit, includeTracks)

    override suspend fun getAlbumIdByTrackId(
        type: IMetadataService.MetadataType,
        trackId: String
    ): String? = getService(type).getAlbumIdByTrackId(type, trackId)

    override suspend fun getImageUrlByAlbumId(
        type: IMetadataService.MetadataType,
        albumId: String
    ): List<IMetadataService.Image> = getService(type).getImageUrlByAlbumId(type, albumId)

    override suspend fun getArtistByMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): IMetadataService.Artist? = getService(type).getArtistByMbId(type, mbId)

    override suspend fun getAlbumByMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): IMetadataService.Album? = getService(type).getAlbumByMbId(type, mbId)

    override suspend fun getTrackByMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): IMetadataService.Track? = getService(type).getTrackByMbId(type, mbId)

    override suspend fun getImageUrlByArtistMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): List<IMetadataService.Image> = getService(type).getImageUrlByArtistMbId(type, mbId)

    override suspend fun getImageUrlByAlbumMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): List<IMetadataService.Image> = getService(type).getImageUrlByAlbumMbId(type, mbId)

    override suspend fun getImageUrlByTrackMbId(
        type: IMetadataService.MetadataType,
        mbId: PlatformUUID
    ): List<IMetadataService.Image> = getService(type).getImageUrlByTrackMbId(type, mbId)

    override suspend fun getImageUrlsByAlbumIds(
        type: IMetadataService.MetadataType,
        albumIds: List<String>
    ): Map<String, List<IMetadataService.Image>> = getService(type).getImageUrlsByAlbumIds(type, albumIds)

    override suspend fun getImageUrlByImageId(
        type: IMetadataService.MetadataType,
        imageId: PlatformUUID
    ): String? = getService(type).getImageUrlByImageId(type, imageId)

    override suspend fun getTrackById(
        type: IMetadataService.MetadataType,
        trackId: String
    ): IMetadataService.Track? = getService(type).getTrackById(type, trackId)

    override suspend fun getTracksByIds(
        type: IMetadataService.MetadataType,
        trackIds: List<String>
    ): List<IMetadataService.Track> = getService(type).getTracksByIds(type, trackIds)

    override suspend fun getAlbumsByIds(
        type: IMetadataService.MetadataType,
        albumIds: List<String>
    ): List<IMetadataService.Album> = getService(type).getAlbumsByIds(type, albumIds)

    override suspend fun albumExistsById(
        type: IMetadataService.MetadataType,
        albumId: String
    ): Boolean = getService(type).albumExistsById(type, albumId)

    override suspend fun getArtistsByIds(
        type: IMetadataService.MetadataType,
        artistIds: List<String>
    ): List<IMetadataService.Artist> = getService(type).getArtistsByIds(type, artistIds)
}
