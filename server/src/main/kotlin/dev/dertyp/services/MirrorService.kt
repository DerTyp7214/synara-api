package dev.dertyp.services

import dev.dertyp.core.fetchBatchedResults
import dev.dertyp.core.fetchBatchedResultsByIdKeyset
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistAlias
import dev.dertyp.data.ArtistSplitAlias
import dev.dertyp.data.Image
import dev.dertyp.data.Playlist
import dev.dertyp.data.RemoteServerPaths
import dev.dertyp.data.Song
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.db.ArtistAliasTable
import dev.dertyp.db.ArtistSplitAliasTable
import dev.dertyp.db.ImageTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.inject
import java.util.UUID

class MirrorRpcService(
    private val user: User,
    private val mirrorService: MirrorService
) : IMirrorService {
    override suspend fun getServerPaths(): RemoteServerPaths {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getServerPaths()
    }

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

    override fun getSongData(songId: UUID, quality: Int, chunkSize: Int): Flow<ByteArray> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getSongData(songId, quality, chunkSize)
    }

    override fun getUsers(): Flow<User> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getUsers()
    }

    override fun getSongsByPlaylist(playlistId: UUID): Flow<Song> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getSongsByPlaylist(playlistId)
    }

    override fun getSongsByUserPlaylist(playlistId: UUID): Flow<Song> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getSongsByUserPlaylist(playlistId)
    }

    override fun getLikedSongs(userId: UUID): Flow<Song> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can mirror")
        return mirrorService.getLikedSongs(userId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MirrorService : Service() {
    private val songService by inject<SongService>()
    private val artistService by inject<ArtistService>()
    private val albumService by inject<AlbumService>()
    private val playlistService by inject<PlaylistService>()
    private val userPlaylistService by inject<UserPlaylistService>()
    private val imageService by inject<ImageService>()
    private val storageService by inject<StorageService>()
    private val userService by inject<UserService>()

    fun getServerPaths(): RemoteServerPaths = RemoteServerPaths(
        tracksPath = storageService.tracksPath,
        albumsPath = storageService.albumsPath,
        playlistsPath = storageService.playlistsPath,
        customAudioPath = storageService.customAudioPath,
        secondaryTracksPaths = storageService.secondaryTracksPaths
    )

    fun getUsers(): Flow<User> = flow {
        userService.queryUser().forEach { emit(it.copy(passwordHash = "")) }
    }.flowOn(Dispatchers.IO)

    fun getSongsByPlaylist(playlistId: UUID): Flow<Song> =
        songService.songIdsByPlaylist(playlistId).chunked(100).flatMapConcat { songService.byIds(it).asFlow() }

    fun getSongsByUserPlaylist(playlistId: UUID): Flow<Song> =
        songService.songIdsByUserPlaylist(playlistId).chunked(100).flatMapConcat { songService.byIds(it).asFlow() }

    fun getLikedSongs(userId: UUID): Flow<Song> =
        songService.likedSongIds(true, userId).chunked(100).flatMapConcat { songService.byIds(it).asFlow() }

    fun getSongs(): Flow<Song> = songService.allSongsFlow()

    fun getArtists(): Flow<Artist> = artistService.allArtistsFlow()

    fun getArtistAliases(): Flow<ArtistAlias> = flow {
        ArtistAliasTable.selectAll().fetchBatchedResultsByIdKeyset(ArtistAliasTable.id, 1000) { batch ->
            for (row in batch) {
                emit(ArtistAlias(
                    artistId = row[ArtistAliasTable.artistId].value,
                    name = row[ArtistAliasTable.name]
                ))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getArtistSplitAliases(): Flow<ArtistSplitAlias> = flow {
        ArtistSplitAliasTable.selectAll().fetchBatchedResults(1000) { batch ->
            for (row in batch) {
                emit(ArtistSplitAlias(
                    artistId = row[ArtistSplitAliasTable.artistId].value,
                    name = row[ArtistSplitAliasTable.name]
                ))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getAlbums(): Flow<Album> = albumService.allAlbumsFlow()

    fun getPlaylists(): Flow<Playlist> = playlistService.allPlaylistsFlow()

    fun getUserPlaylists(): Flow<UserPlaylist> = userPlaylistService.allPlaylistsFlow(null)

    fun getImageMetadata(): Flow<Image> = flow {
        ImageTable.selectAll().fetchBatchedResultsByIdKeyset(ImageTable.id, 1000) { batch ->
            for (row in batch) {
                emit(imageService.map(row))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getSongData(songId: UUID, quality: Int, chunkSize: Int = 4096): Flow<ByteArray> {
        if (quality == -1) return songService.streamSong(songId, 0, chunkSize) ?: flow { }
        return songService.downloadSong(songId, quality, 0, chunkSize) ?: flow { }
    }
}
