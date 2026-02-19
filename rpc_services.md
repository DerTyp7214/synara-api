# Kotlin RPC Services Documentation

This document lists all available Kotlin RPC services, their serializers, authentication requirements, and a breakdown of their interfaces.

## Overview

The application uses `kotlinx.rpc` with Ktor for RPC communication.

**Serialization:**
- **CBOR** is used as the default serialization format for RPC calls, configured in `Routing.kt`.
- **JSON** and **Protobuf** are also available for content negotiation but RPC specifically uses CBOR.

**Authentication:**
- Most services are protected and require a valid JWT token.
- The `IAuthService` and `IServerStatsService` are publicly accessible (though `IServerStatsService` might be intended for monitoring).
- Authenticated services are registered within a `jwtService.authenticated` block.

---

## Services

### IServerStatsService
*   **Path:** `/rpc`
*   **Authentication:** Public
*   **Description:** Provides server statistics and health check information.
*   **Interface:**
    *   `getStats()`: Returns `ServerStats` object containing server metrics.
    *   `health()`: Returns a boolean indicating if the server is healthy.

### IAuthService
*   **Path:** `/rpc/auth`
*   **Authentication:** Public
*   **Description:** Handles user authentication and token management.
*   **Interface:**
    *   `authenticate(username, password)`: Authenticates a user and returns an `AuthenticationResponse` (tokens).
    *   `refreshToken(refreshToken)`: Refreshes the access token using a refresh token.

### IIndexer
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages indexing of media content.
*   **Interface:**
    *   `start()`: Starts the indexing process and returns a `Flow<String>` of status updates.

### IUserService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages user data.
*   **Interface:**
    *   `findUserById(id)`: Retrieves a user by their UUID.
    *   `findUserByUsername(username)`: Retrieves a user by their username.
    *   `me()`: Retrieves the currently authenticated user's profile.

### ISongService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Comprehensive service for managing and retrieving songs.
*   **Interface:**
    *   `setLiked(id, liked, addedAt)`: Marks a song as liked/unliked.
    *   `setLyrics(id, lyrics)`: Updates lyrics for a song.
    *   `byId(id)`: Gets a song by ID.
    *   `byIds(ids)`: Gets multiple songs by IDs. **Preserves order of input IDs.**
    *   `byTitle`, `byArtist`, `byAlbum`, `byPlaylist`, `byUserPlaylist`: Search/Filter songs by various criteria with pagination.
    *   `byTidalTrackIds`, `byTidalTracks`: Retrieves songs based on Tidal metadata.
    *   `likedSongs`, `allSongs`: Retrieves lists of songs.
    *   `deleteSongs(ids)`: Deletes specified songs.
    *   `rankedSearch(...)`: Performs a ranked search for songs.
    *   `streamSong(id, offset)`: Returns a `Flow<ByteArray>` for streaming song audio.
    *   `getStreamSize(id)`: Gets the size of the song stream.
    *   `allSongIds`, `likedSongIds`, `songIdsBy...`: Returns Flows of UUIDs for various song collections.

### IAlbumService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages album data.
*   **Interface:**
    *   `byId(id)`: Gets an album by ID.
    *   `byIds(ids)`: Gets multiple albums.
    *   `versions(id)`: Gets different versions of an album.
    *   `byName(...)`: Search albums by name.
    *   `rankedSearch(...)`: Ranked search for albums.
    *   `allAlbums(...)`: Retrieves all albums paginated.
    *   `deleteAlbums(ids)`: Deletes albums.
    *   `byArtist(...)`: Gets albums by a specific artist.

### IImageService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Handles image retrieval and creation (covers, avatars, etc.).
*   **Interface:**
    *   `byId(id)`: Gets image metadata by ID.
    *   `byHash(hash)`: Gets image by hash.
    *   `getCoverHashes(hashes)`: Resolves multiple image hashes to UUIDs.
    *   `getImageData(id, size)`: Downloads the raw image data.
    *   `createImage(bytes, origin)`: Uploads a new image.

### ILyricsSearch
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Searches for lyrics.
*   **Interface:**
    *   `searchLyrics(artist, title, syncedOnly)`: Searches for lyrics matching the criteria.

### IArtistService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages artist data.
*   **Interface:**
    *   `byId(id)`: Gets an artist by ID.
    *   `byIds(ids)`: Gets multiple artists.
    *   `rankedSearch(...)`: Ranked search for artists.
    *   `byGroup(...)`: Gets artists belonging to a group.
    *   `mergeArtists(mergeArtists)`: Merges multiple artist records into a single new artist record. This process involves creating a new artist, reassigning all songs and albums from the source artists to the new one, creating aliases for the old names, and then deleting the original source artist records.
    *   `allArtists(...)`: Retrieves all artists paginated.

### IFavSyncService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages synchronization of favorites with external services.
*   **Interface:**
    *   `getLatestFavSync(service)`: Gets the last sync timestamp for a service.
    *   `insertFavSync(service, syncedAt)`: Records a new sync timestamp.

### IDownloadService
*   **Path:** `/rpc/services` (also available at `/tdn` with separate auth logic)
*   **Authentication:** Required (JWT)
*   **Description:** Manages downloads from external sources (like Tidal).
*   **Interface:**
    *   `logs()`: Returns a flow of download logs.
    *   `currentDownload()`: Gets the currently active download.
    *   `downloadQueue()`: Gets the queue of pending downloads.
    *   `finishedDownloads()`: Gets a list of completed downloads.
    *   `syncFavouritesAvailable()`: Checks if favorites sync is available.
    *   `syncFavourites()`: Triggers favorites sync.
    *   `downloadTidalIds(...)`: Queues downloads by Tidal IDs.
    *   `existsByTidalId(...)`: Checks if content exists by Tidal ID.
    *   `setTidalDownloadService(...)`, `getTidalDownloadService()`: Configures the underlying download service.
    *   `tidalDownloadAuthorized()`, `tidalDownloadLogin()`: Manages Tidal download authorization.
    *   `tidalSyncAuthorized()`, `getAuthUrl()`: Manages Tidal sync authorization.
    *   `killAllChildProcesses()`: Stops active download processes.
    *   `searchTidal(...)`: Searches for content on Tidal.

### IPlaylistService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages system/global playlists.
*   **Interface:**
    *   `byId(id)`: Gets a playlist by ID.
    *   `byIds(ids)`: Gets multiple playlists.
    *   `byIdFull(id)`: Gets full playlist details including entries.
    *   `byName(name)`: Gets a playlist by name.
    *   `rankedSearch(...)`: Ranked search for playlists.
    *   `allPlaylists(...)`: Retrieves all playlists paginated.
    *   `delete(id)`: Deletes a playlist.

### IUserPlaylistService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages user-specific playlists.
*   **Interface:**
    *   `byId(id)`: Gets a user playlist by ID.
    *   `byIds(ids)`: Gets multiple user playlists.
    *   `rankedSearch(...)`: Ranked search for user playlists.
    *   `allPlaylists(...)`: Retrieves all user playlists paginated.
    *   `delete(id)`: Deletes a user playlist.
    *   `getOrAddPlaylist(...)`: Gets or creates a playlist.
    *   `addToPlaylist(...)`: Adds songs to a playlist.
    *   `removeFromPlaylist(...)`: Removes songs from a playlist.
    *   `setPlaylistImage(...)`: Sets the cover image for a playlist.

### ISessionService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Manages user sessions.
*   **Interface:**
    *   `deactivateSession(sessionId)`: Terminates a specific session.
    *   `getSessions()`: Retrieves all sessions (active and inactive) for the current user.

### IPlaybackService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Synchronizes and manages playback state across devices.
*   **Interface:**
    *   `getPlaybackState(sessionId)`: Gets the current playback state for a session.
    *   `setPlaybackState(sessionId, state)`: Updates the playback state.
    *   `observePlaybackState(sessionId)`: Returns a `Flow<PlaybackState>` to watch state changes.

### ICustomAudioService
*   **Path:** `/rpc/services`
*   **Authentication:** Required (JWT)
*   **Description:** Handles uploading of custom audio files.
*   **Interface:**
    *   `uploadCustomAudio(fileData, fileName, metadata)`: Uploads a custom audio file and returns its UUID.
