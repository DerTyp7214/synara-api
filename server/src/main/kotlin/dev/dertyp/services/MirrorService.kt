package dev.dertyp.services

import dev.dertyp.core.fetchBatchedResults
import dev.dertyp.data.*
import dev.dertyp.db.ArtistAliasTable
import dev.dertyp.db.ArtistSplitAliasTable
import dev.dertyp.db.ImageTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.inject
import java.util.UUID

class MirrorRpcService(
    private val user: User,
    private val mirrorService: MirrorService
) : IMirrorService {
    override fun getSongs(): Flow<Song> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getSongs()
    }

    override fun getArtists(): Flow<Artist> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getArtists()
    }

    override fun getArtistAliases(): Flow<ArtistAlias> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getArtistAliases()
    }

    override fun getArtistSplitAliases(): Flow<ArtistSplitAlias> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getArtistSplitAliases()
    }

    override fun getAlbums(): Flow<Album> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getAlbums()
    }

    override fun getPlaylists(): Flow<Playlist> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getPlaylists()
    }

    override fun getUserPlaylists(): Flow<UserPlaylist> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getUserPlaylists()
    }

    override fun getImageMetadata(): Flow<Image> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getImageMetadata()
    }

    override fun getSongData(songId: UUID, quality: Int): Flow<ByteArray> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getSongData(songId, quality)
    }
}

class MirrorService : Service() {
    private val songService by inject<SongService>()
    private val artistService by inject<ArtistService>()
    private val albumService by inject<AlbumService>()
    private val playlistService by inject<PlaylistService>()
    private val userPlaylistService by inject<UserPlaylistService>()
    private val imageService by inject<ImageService>()

    fun getSongs(): Flow<Song> = songService.allSongsFlow()

    fun getArtists(): Flow<Artist> = artistService.allArtistsFlow()

    fun getArtistAliases(): Flow<ArtistAlias> = flow {
        ArtistAliasTable.selectAll().fetchBatchedResults(1000) { batch ->
            batch.forEach { row ->
                emit(ArtistAlias(
                    artistId = row[ArtistAliasTable.artistId].value,
                    name = row[ArtistAliasTable.name]
                ))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getArtistSplitAliases(): Flow<ArtistSplitAlias> = flow {
        ArtistSplitAliasTable.selectAll().fetchBatchedResults(1000) { batch ->
            batch.forEach { row ->
                emit(ArtistSplitAlias(
                    artistId = row[ArtistSplitAliasTable.artistId].value,
                    name = row[ArtistSplitAliasTable.name]
                ))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getAlbums(): Flow<Album> = albumService.allAlbumsFlow()

    fun getPlaylists(): Flow<Playlist> = flow {
        playlistService.allPlaylists(0, Int.MAX_VALUE).data.forEach { emit(it) }
    }

    fun getUserPlaylists(): Flow<UserPlaylist> = flow {
        userPlaylistService.allPlaylists(null, 0, Int.MAX_VALUE).data.forEach { emit(it) }
    }

    fun getImageMetadata(): Flow<Image> = flow {
        ImageTable.selectAll().fetchBatchedResults(1000) { batch ->
            batch.forEach { row ->
                emit(imageService.map(row))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getSongData(songId: UUID, quality: Int): Flow<ByteArray> {
        if (quality == -1) return songService.streamSong(songId, 0) ?: flow { }
        return songService.downloadSong(songId, quality, 0) ?: flow { }
    }
}
