# Synara RPC Services Documentation

This document provides a comprehensive breakdown of all available RPC services in the Synara API. All services utilize `kotlinx.rpc` with CBOR serialization over WebSockets.

## Data Model Field Explanations

To better understand the RPC interfaces, here are explanations for some common fields across different data classes:

### MusicBrainz Identifiers
The `musicBrainzId` (or `musicbrainzId`) field contains a UUID from the MusicBrainz database, used for metadata enrichment and persistent identification. It represents different entities depending on the class:
- **Song / UserSong**: Represents a MusicBrainz **Recording** ID.
- **Album**: Represents a MusicBrainz **Release** ID.
- **Artist**: Represents a MusicBrainz **Artist** ID.

### Source Identifiers
- **originalId**: The unique identifier of the item from its original source (e.g., a Tidal Track ID or Album ID).
- **originalUrl**: The full URL to the item on its original platform.

### Media & State
- **coverId**: Synara's internal UUID for the associated cover image.
- **explicit**: Boolean flag indicating if the content contains explicit material. *Disclaimer: This field may not always be accurate depending on the provided source metadata.*
- **isFavourite**: User-specific boolean indicating if they have "liked" the item.
- **transcodedTo**: A list of bitrates (integers) for which a transcoded version of the song exists on the server.

### Song vs. UserSong
- **Song**: Contains core metadata about a track (title, duration, path, etc.) that is common for all users.
- **UserSong**: Extends the track metadata with user-specific information, such as whether it is marked as a favorite and when it was added to the user's collection.

---

## Core & Authentication

### IAuthService
Handles user login and session security.
- **Path:** `/rpc/auth`
- **Authentication:** Public

| Function       | Parameters           | Returns                  | Admin Only | Potential Errors                                    | Description                            |
|:---------------|:---------------------|:-------------------------|:----------:|:----------------------------------------------------|:---------------------------------------|
| `authenticate` | `username, password` | `AuthenticationResponse` |     No     | `IllegalArgumentException`, `IllegalStateException` | Logs in a user and returns JWT tokens. |
| `refreshToken` | `refreshToken`       | `AuthenticationResponse` |     No     | `IllegalArgumentException`, `IllegalStateException` | Refreshes an expired access token.     |

### IUserService
Manages user profiles and identities.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function             | Parameters | Returns      | Admin Only | Potential Errors        | Description                                        |
|:---------------------|:-----------|:-------------|:----------:|:------------------------|:---------------------------------------------------|
| `findUserById`       | `id`       | `User?`      |     No     | -                       | Look up a user by their unique ID.                 |
| `findUserByUsername` | `username` | `User?`      |     No     | -                       | Look up a user by their username.                  |
| `me`                 | -          | `User`       |     No     | -                       | Get the profile of the current authenticated user. |
| `getAllUsers`        | -          | `List<User>` |  **Yes**   | `IllegalStateException` | List all users on the server.                      |
| `setProfileImage`    | `bytes`    | `Unit`       |     No     | -                       | Update the current user's avatar.                  |
| `setDisplayName`     | `name`     | `Unit`       |     No     | -                       | Update the current user's display name.            |

### ISessionService
Manages active user sessions and connected devices.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function            | Parameters  | Returns         | Admin Only | Potential Errors | Description                             |
|:--------------------|:------------|:----------------|:----------:|:-----------------|:----------------------------------------|
| `deactivateSession` | `sessionId` | `Unit`          |     No     | -                | Terminate a specific user session.      |
| `getSessions`       | -           | `List<Session>` |     No     | -                | List all sessions for the current user. |

---

## Media Management

### ISongService
The primary interface for song discovery, streaming, and metadata.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function                | Parameters                                   | Returns                       | Admin Only | Potential Errors                       | Description                             |
|:------------------------|:---------------------------------------------|:------------------------------|:----------:|:---------------------------------------|:----------------------------------------|
| `byId`                  | `id`                                         | `UserSong?`                   |     No     | -                                      | Get song by ID.                         |
| `byIds`                 | `ids`                                        | `List<UserSong>`              |     No     | -                                      | Get multiple songs by ID.               |
| `byTitle`               | `page, pageSize, title`                      | `PaginatedResponse<UserSong>` |     No     | -                                      | Search songs by title.                  |
| `byArtist`              | `page, pageSize, artistId`                   | `PaginatedResponse<UserSong>` |     No     | -                                      | List songs by artist.                   |
| `likedByArtist`         | `page, pageSize, artistId, explicit`         | `PaginatedResponse<UserSong>` |     No     | -                                      | List liked songs by artist.             |
| `byAlbum`               | `page, pageSize, albumId`                    | `PaginatedResponse<UserSong>` |     No     | -                                      | List songs in an album.                 |
| `byPlaylist`            | `page, pageSize, playlistId`                 | `PaginatedResponse<UserSong>` |     No     | -                                      | List songs in a system playlist.        |
| `byUserPlaylist`        | `page, pageSize, playlistId`                 | `PaginatedResponse<UserSong>` |     No     | -                                      | List songs in a user playlist.          |
| `allSongs`              | `page, pageSize, explicit, tags, invertTags` | `PaginatedResponse<UserSong>` |     No     | -                                      | Get all songs with filtering.           |
| `likedSongs`            | `page, pageSize, explicit`                   | `PaginatedResponse<UserSong>` |     No     | -                                      | Get liked songs.                        |
| `rankedSearch`          | `page, pageSize, query, explicit, liked`     | `PaginatedResponse<UserSong>` |     No     | -                                      | Perform a ranked search.                |
| `streamSong`            | `id, offset, chunkSize`                      | `Flow<ByteArray>?`            |     No     | `IOException`, `FileNotFoundException` | Stream song audio.                      |
| `downloadSong`          | `id, quality, offset, chunkSize`             | `Flow<ByteArray>?`            |     No     | `IOException`, `IllegalStateException` | Download song audio.                    |
| `getStreamSize`         | `id`                                         | `Long`                        |     No     | -                                      | Get audio stream size.                  |
| `getDownloadSize`       | `id, quality`                                | `Long`                        |     No     | -                                      | Get download size for specific quality. |
| `setLiked`              | `id, liked, addedAt`                         | `UserSong?`                   |     No     | -                                      | Toggle favorite status.                 |
| `setLyrics`             | `id, lyrics`                                 | `UserSong?`                   |     No     | -                                      | Manually set song lyrics.               |
| `setArtists`            | `id, artistIds`                              | `UserSong?`                   |     No     | -                                      | Update song artists.                    |
| `setMusicBrainzId`      | `id, musicBrainzId`                          | `UserSong?`                   |     No     | -                                      | Link to MusicBrainz.                    |
| `fetchMusicBrainzId`    | `id`                                         | `UserSong?`                   |     No     | -                                      | Trigger MusicBrainz ID fetching.        |
| `byMusicBrainzId`       | `musicBrainzId`                              | `List<UserSong>`              |     No     | -                                      | Find songs by MBID.                     |
| `byTidalTrackIds`       | `ids`                                        | `List<UserSong>`              |     No     | -                                      | Find songs by Tidal IDs.                |
| `deleteSongs`           | `ids`                                        | `Boolean`                     |     No     | -                                      | Delete songs from library.              |
| `allSongIds`            | `explicit, tags, invertTags`                 | `Flow<PlatformUUID>`          |     No     | -                                      | Stream all song IDs.                    |
| `likedSongIds`          | `explicit`                                   | `Flow<PlatformUUID>`          |     No     | -                                      | Stream all liked song IDs.              |
| `songIdsByArtist`       | `artistId`                                   | `Flow<PlatformUUID>`          |     No     | -                                      | Stream song IDs by artist.              |
| `songIdsByAlbum`        | `albumId`                                    | `Flow<PlatformUUID>`          |     No     | -                                      | Stream song IDs by album.               |
| `songIdsByPlaylist`     | `playlistId`                                 | `Flow<PlatformUUID>`          |     No     | -                                      | Stream song IDs by playlist.            |
| `songIdsByUserPlaylist` | `playlistId`                                 | `Flow<PlatformUUID>`          |     No     | -                                      | Stream song IDs by user playlist.       |

### IAlbumService
Manages albums and their metadata.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function             | Parameters                          | Returns                    | Admin Only | Potential Errors | Description                          |
|:---------------------|:------------------------------------|:---------------------------|:----------:|:-----------------|:-------------------------------------|
| `byId`               | `id`                                | `Album?`                   |     No     | -                | Get album by ID.                     |
| `byIds`              | `ids`                               | `List<Album>`              |     No     | -                | Get multiple albums.                 |
| `versions`           | `id`                                | `List<Album>`              |     No     | -                | List different versions of an album. |
| `byName`             | `page, pageSize, name`              | `PaginatedResponse<Album>` |     No     | -                | Search albums by name.               |
| `byArtist`           | `page, pageSize, artistId, singles` | `PaginatedResponse<Album>` |     No     | -                | List albums by artist.               |
| `allAlbums`          | `page, pageSize`                    | `PaginatedResponse<Album>` |     No     | -                | Get all albums.                      |
| `rankedSearch`       | `page, pageSize, query`             | `PaginatedResponse<Album>` |     No     | -                | Ranked album search.                 |
| `updateAlbum`        | `album`                             | `Album?`                   |     No     | -                | Update album metadata.               |
| `deleteAlbums`       | `ids`                               | `Boolean`                  |     No     | -                | Delete albums.                       |
| `fetchMusicBrainzId` | `id`                                | `Album?`                   |     No     | -                | Fetch MBID for album.                |

### IArtistService
Manages artist data and complex library maintenance.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function                          | Parameters                            | Returns                                | Admin Only | Potential Errors | Description                         |
|:----------------------------------|:--------------------------------------|:---------------------------------------|:----------:|:-----------------|:------------------------------------|
| `byId`                            | `id`                                  | `Artist?`                              |     No     | -                | Get artist by ID.                   |
| `byIds`                           | `ids`                                 | `List<Artist>`                         |     No     | -                | Get multiple artists.               |
| `allArtists`                      | `page, pageSize`                      | `PaginatedResponse<Artist>`            |     No     | -                | Get all artists.                    |
| `rankedSearch`                    | `page, pageSize, query`               | `PaginatedResponse<Artist>`            |     No     | -                | Ranked artist search.               |
| `createArtist`                    | `name, isGroup, about, musicBrainzId` | `Artist`                               |     No     | -                | Manually create an artist.          |
| `setGroup`                        | `id, artistIds`                       | `Artist?`                              |     No     | -                | Set sub-artists for a group.        |
| `byGroup`                         | `page, pageSize, groupId`             | `PaginatedResponse<Artist>`            |     No     | -                | List artists in a group.            |
| `mergeArtists`                    | `mergeArtists`                        | `Artist?`                              |     No     | -                | Merge multiple artist records.      |
| `splitArtist`                     | `splitArtist`                         | `List<Artist>`                         |     No     | -                | Split an artist record.             |
| `fetchMusicBrainzId`              | `id`                                  | `Artist?`                              |     No     | -                | Fetch MBID for artist.              |
| `setMusicBrainzId`                | `id, musicBrainzId`                   | `Artist?`                              |     No     | -                | Link artist to MusicBrainz.         |
| `searchArtistOnMusicBrainz`       | `query, page, pageSize`               | `PaginatedResponse<MusicBrainzArtist>` |     No     | -                | Search MusicBrainz.                 |
| `artistsWithoutMusicBrainzIdFlow` | -                                     | `Flow<Artist>`                         |     No     | -                | Stream artists missing MBID.        |
| `artistIdsWithoutMusicBrainzId`   | -                                     | `Flow<PlatformUUID>`                   |     No     | -                | Stream IDs of artists missing MBID. |

### IPlaylistService & IUserPlaylistService
Management of system and personal playlists.
- **Path:** `/rpc/services`
- **Authentication:** Required

#### System Playlists
| Function       | Parameters              | Returns                              | Admin Only | Potential Errors | Description                    |
|:---------------|:------------------------|:-------------------------------------|:----------:|:-----------------|:-------------------------------|
| `byId`         | `id`                    | `Playlist?`                          |     No     | -                | Get playlist by ID.            |
| `byIds`        | `ids`                   | `List<Playlist>`                     |     No     | -                | Get multiple playlists.        |
| `byIdFull`     | `id`                    | `Pair<String, List<PlaylistEntry>>?` |     No     | -                | Get playlist with all entries. |
| `byName`       | `name`                  | `Playlist?`                          |     No     | -                | Get playlist by name.          |
| `allPlaylists` | `page, pageSize`        | `PaginatedResponse<Playlist>`        |     No     | -                | Get all system playlists.      |
| `rankedSearch` | `page, pageSize, query` | `PaginatedResponse<Playlist>`        |     No     | -                | Search system playlists.       |
| `delete`       | `id`                    | `Boolean`                            |     No     | -                | Delete a system playlist.      |

#### User Playlists
| Function             | Parameters                       | Returns                           | Admin Only | Potential Errors | Description                         |
|:---------------------|:---------------------------------|:----------------------------------|:----------:|:-----------------|:------------------------------------|
| `byId`               | `id`                             | `UserPlaylist?`                   |     No     | -                | Get user playlist by ID.            |
| `byIds`              | `ids`                            | `List<UserPlaylist>`              |     No     | -                | Get multiple user playlists.        |
| `allPlaylists`       | `creator, page, pageSize`        | `PaginatedResponse<UserPlaylist>` |     No     | -                | Get all user playlists.             |
| `rankedSearch`       | `creator, page, pageSize, query` | `PaginatedResponse<UserPlaylist>` |     No     | -                | Search user playlists.              |
| `getOrAddPlaylist`   | `user, identifier, playlist`     | `PlatformUUID`                    |     No     | -                | Create or retrieve a user playlist. |
| `addToPlaylist`      | `id, songIds`                    | `List<PlatformUUID>`              |     No     | -                | Add songs to playlist.              |
| `removeFromPlaylist` | `id, songIds`                    | `Int`                             |     No     | -                | Remove songs from playlist.         |
| `setPlaylistImage`   | `id, imageId`                    | `Boolean`                         |     No     | -                | Set playlist cover.                 |
| `delete`             | `id`                             | `Boolean`                         |     No     | -                | Delete a user playlist.             |

### ICustomAudioService
Handles manual audio uploads.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function            | Parameters                     | Returns         | Admin Only | Potential Errors | Description                 |
|:--------------------|:-------------------------------|:----------------|:----------:|:-----------------|:----------------------------|
| `uploadCustomAudio` | `fileData, fileName, metadata` | `PlatformUUID?` |     No     | -                | Upload a custom audio file. |

### IIndexer
Local file system media scanning.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function | Parameters | Returns        | Admin Only | Potential Errors | Description                         |
|:---------|:-----------|:---------------|:----------:|:-----------------|:------------------------------------|
| `start`  | -          | `Flow<String>` |     No     | -                | Start indexing and stream progress. |

---

## Metadata & Lyrics

### ILyricsService & ILyricsSearch
Syncing and searching for lyrics.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function           | Parameters                  | Returns         | Admin Only | Potential Errors   | Description                              |
|:-------------------|:----------------------------|:----------------|:----------:|:-------------------|:-----------------------------------------|
| `getSyncedLyrics`  | `songId`                    | `SyncedLyrics?` |     No     | -                  | Get time-synced lyrics.                  |
| `transcribeLyrics` | `songId, lyrics`            | `SyncedLyrics?` |     No     | `RuntimeException` | Trigger AI transcription.                |
| `startSyncWorker`  | -                           | `Boolean`       |     No     | -                  | Start the background lyrics sync worker. |
| `searchLyrics`     | `artist, title, syncedOnly` | `List<String>`  |     No     | `RuntimeException` | Search external lyrics providers.        |

### IMusicBrainzService
Metadata retrieval from MusicBrainz.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function          | Parameters | Returns                    | Admin Only | Potential Errors | Description                    |
|:------------------|:-----------|:---------------------------|:----------:|:-----------------|:-------------------------------|
| `getArtist`       | `id`       | `MusicBrainzArtist?`       |     No     | -                | Fetch MB artist record.        |
| `getRecording`    | `id`       | `MusicBrainzRecording?`    |     No     | -                | Fetch MB recording record.     |
| `getRelease`      | `id`       | `MusicBrainzRelease?`      |     No     | -                | Fetch MB release record.       |
| `getReleaseGroup` | `id`       | `MusicBrainzReleaseGroup?` |     No     | -                | Fetch MB release group record. |

### IReleaseService
Track and follow artist releases.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function             | Parameters       | Returns                            | Admin Only | Potential Errors | Description                                    |
|:---------------------|:-----------------|:-----------------------------------|:----------:|:-----------------|:-----------------------------------------------|
| `followArtist`       | `musicBrainzId`  | `Boolean`                          |     No     | -                | Follow an artist.                              |
| `unfollowArtist`     | `artistId`       | `Boolean`                          |     No     | -                | Unfollow an artist.                            |
| `getFollowedArtists` | -                | `List<FollowedArtist>`             |     No     | -                | List followed artists.                         |
| `getRecentReleases`  | `page, pageSize` | `PaginatedResponse<RecentRelease>` |     No     | -                | Feed of recent releases from followed artists. |

---

## Downloads & Sync

### IDownloadService
Management of the integrated media downloader (Tidal).
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function                  | Parameters                    | Returns                            | Admin Only | Potential Errors           | Description                                   |
|:--------------------------|:------------------------------|:-----------------------------------|:----------:|:---------------------------|:----------------------------------------------|
| `logs`                    | -                             | `Flow<LogLine>`                    |     No     | -                          | Stream real-time download logs.               |
| `currentDownload`         | -                             | `DownloadQueueEntry?`              |     No     | -                          | Get current active download.                  |
| `downloadQueue`           | -                             | `List<DownloadQueueEntry>`         |     No     | -                          | Get pending download queue.                   |
| `finishedDownloads`       | -                             | `List<FinishedDownloadQueueEntry>` |     No     | -                          | List completed downloads.                     |
| `downloadTidalIds`        | `ids, type`                   | `Unit`                             |     No     | -                          | Queue Tidal IDs for download.                 |
| `existsByTidalId`         | `id, type`                    | `Boolean`                          |     No     | -                          | Check if Tidal content is already downloaded. |
| `syncFavourites`          | -                             | `Unit`                             |     No     | `IllegalStateException`    | Synchronize Tidal favorites.                  |
| `syncFavouritesAvailable` | -                             | `Boolean`                          |     No     | -                          | Check if fav sync is available.               |
| `tidalDownloadAuthorized` | -                             | `Boolean`                          |     No     | -                          | Check Tidal download auth.                    |
| `tidalDownloadLogin`      | -                             | `Flow<String>`                     |     No     | -                          | Trigger Tidal login flow.                     |
| `tidalSyncAuthorized`     | -                             | `Boolean`                          |     No     | -                          | Check Tidal sync auth.                        |
| `getAuthUrl`              | -                             | `String`                           |     No     | `IllegalArgumentException` | Get Tidal OAuth URL.                          |
| `killAllChildProcesses`   | -                             | `Unit`                             |     No     | -                          | Stop all active downloaders.                  |
| `searchTidal`             | `query, title, artist, count` | `List<TidalSong>`                  |     No     | `IllegalStateException`    | Search directly on Tidal.                     |
| `getTidalDownloadService` | -                             | `TidalDownloadService`             |     No     | -                          | Get current downloader config.                |
| `setTidalDownloadService` | `service`                     | `Unit`                             |     No     | -                          | Set downloader config.                        |

### IFavSyncService
Synchronization status tracking.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function           | Parameters          | Returns    | Admin Only | Potential Errors | Description                  |
|:-------------------|:--------------------|:-----------|:----------:|:-----------------|:-----------------------------|
| `getLatestFavSync` | `service`           | `FavSync?` |     No     | -                | Get last sync timestamp.     |
| `insertFavSync`    | `service, syncedAt` | `Int`      |     No     | -                | Record a new sync timestamp. |

---

## System & Administration

### IServerStatsService
Basic server monitoring.
- **Path:** `/rpc`
- **Authentication:** Public

| Function       | Parameters | Returns       | Admin Only | Potential Errors | Description                             |
|:---------------|:-----------|:--------------|:----------:|:-----------------|:----------------------------------------|
| `getStats`     | -          | `ServerStats` |     No     | -                | Detailed system performance metrics.    |
| `health`       | -          | `Boolean`     |     No     | -                | Simple health check.                    |
| `getProxyInfo` | -          | `ProxyInfo?`  |     No     | -                | Information about reverse proxy status. |

### IBackupService & IUserPlaylistBackupService
System and user data persistence.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function                  | Parameters | Returns               | Admin Only | Potential Errors                                | Description                    |
|:--------------------------|:-----------|:----------------------|:----------:|:------------------------------------------------|:-------------------------------|
| `createBackup`            | -          | `BackupResult`        |  **Yes**   | `SecurityException`                             | Create a full system backup.   |
| `listBackups`             | -          | `List<BackupInfo>`    |  **Yes**   | `SecurityException`                             | List available system backups. |
| `loadBackup`              | `fileName` | `Unit`                |  **Yes**   | `SecurityException`, `IllegalArgumentException` | Restore system from backup.    |
| `deleteBackup`            | `fileName` | `Unit`                |  **Yes**   | `SecurityException`                             | Delete a system backup.        |
| `createBackup` (User)     | -          | `Unit`                |     No     | -                                               | Create a user playlist backup. |
| `listBackups` (User)      | -          | `List<BackupInfo>`    |     No     | -                                               | List user playlist backups.    |
| `restoreBackup` (User)    | `fileName` | `Unit`                |     No     | -                                               | Restore user playlists.        |
| `getBackupContent` (User) | `fileName` | `UserPlaylistBackup?` |     No     | -                                               | Peek into user backup content. |
| `deleteBackup` (User)     | `fileName` | `Unit`                |     No     | -                                               | Delete a user backup.          |

### IDbManagementService
Direct database data migration.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function     | Parameters | Returns     | Admin Only | Potential Errors | Description                       |
|:-------------|:-----------|:------------|:----------:|:-----------------|:----------------------------------|
| `exportData` | -          | `ByteArray` |     No     | -                | Export entire database as blob.   |
| `importData` | `data`     | `Unit`      |     No     | -                | Import entire database from blob. |

### IStorageService
Disk usage monitoring.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function          | Parameters | Returns | Admin Only | Potential Errors | Description                               |
|:------------------|:-----------|:--------|:----------:|:-----------------|:------------------------------------------|
| `getTotalStorage` | -          | `Long`  |     No     | -                | Get total music library storage in bytes. |

### IScheduledTaskLogService
Monitoring of background tasks.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function             | Parameters | Returns                                     | Admin Only | Potential Errors    | Description                     |
|:---------------------|:-----------|:--------------------------------------------|:----------:|:--------------------|:--------------------------------|
| `getGroupedLogs`     | -          | `Map<String, List<ScheduledTaskLog>>`       |  **Yes**   | `SecurityException` | Snapshot of task logs.          |
| `getGroupedLogsFlow` | -          | `Flow<Map<String, List<ScheduledTaskLog>>>` |  **Yes**   | `SecurityException` | Reactive flow of task progress. |

---

## Remote & Mirroring

### IMirrorService & IRemoteMirrorService
Instance-to-instance data synchronization.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function                  | Parameters                   | Returns                   | Admin Only | Potential Errors        | Description                           |
|:--------------------------|:-----------------------------|:--------------------------|:----------:|:------------------------|:--------------------------------------|
| `startMirror`             | `config`                     | `Unit`                    |  **Yes**   | `IllegalStateException` | Start mirroring from another server.  |
| `stopMirror`              | -                            | `Unit`                    |  **Yes**   | `IllegalStateException` | Stop active mirror process.           |
| `resetMirror`             | -                            | `Unit`                    |  **Yes**   | `IllegalStateException` | Reset mirror state.                   |
| `getActiveMirrorProgress` | -                            | `Flow<MirrorProgress>`    |  **Yes**   | -                       | Stream mirroring progress updates.    |
| `getRemoteStats`          | `config`                     | `ServerStats`             |  **Yes**   | `IllegalStateException` | Get stats from a remote server.       |
| `getRemoteUsers`          | `config`                     | `List<User>`              |  **Yes**   | `IllegalStateException` | List users on remote server.          |
| `getRemotePlaylists`      | `config`                     | `List<Playlist>`          |  **Yes**   | `IllegalStateException` | List playlists on remote server.      |
| `getRemoteUserPlaylists`  | `config`                     | `List<UserPlaylist>`      |  **Yes**   | `IllegalStateException` | List user playlists on remote server. |
| `getRemoteImageData`      | `config, imageId, size`      | `ByteArray?`              |  **Yes**   | `IllegalStateException` | Fetch image from remote server.       |
| `getProxyInstances`       | `config`                     | `List<ProxyInstanceInfo>` |  **Yes**   | `IllegalStateException` | List proxies on remote server.        |
| `getServerPaths`          | -                            | `RemoteServerPaths`       |  **Yes**   | `IllegalStateException` | List local file system paths.         |
| `getSongs`                | -                            | `Flow<Song>`              |  **Yes**   | `IllegalStateException` | Stream all local songs as raw data.   |
| `getArtists`              | -                            | `Flow<Artist>`            |  **Yes**   | `IllegalStateException` | Stream all local artists.             |
| `getAlbums`               | -                            | `Flow<Album>`             |  **Yes**   | `IllegalStateException` | Stream all local albums.              |
| `getSongData`             | `songId, quality, chunkSize` | `Flow<ByteArray>`         |  **Yes**   | `IllegalStateException` | Stream raw audio for mirroring.       |

### IPlaybackService
Cross-device synchronization of playback status.
- **Path:** `/rpc/services`
- **Authentication:** Required

| Function               | Parameters         | Returns               | Admin Only | Potential Errors | Description                      |
|:-----------------------|:-------------------|:----------------------|:----------:|:-----------------|:---------------------------------|
| `getPlaybackState`     | `sessionId`        | `PlaybackState?`      |     No     | -                | Get current state for a session. |
| `setPlaybackState`     | `sessionId, state` | `Boolean`             |     No     | -                | Update state for a session.      |
| `observePlaybackState` | `sessionId`        | `Flow<PlaybackState>` |     No     | -                | Watch state changes reactively.  |
