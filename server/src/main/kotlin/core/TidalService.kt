package dev.dertyp.core

import dev.dertyp.plugins.RedisCacheObject
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.TidalService
import kotlin.time.Duration.Companion.days

fun TidalService.writeToJedis(track: IMetadataService.Track) {
    jedis?.psetex(
        "tidal_track::${track.id}",
        30.days.inWholeMilliseconds,
        RedisCacheObject.fromObject(track).toString()
    )
}

fun TidalService.getTrackFromJedis(trackId: String): IMetadataService.Track? {
    return jedis?.let { jedis ->
        if (jedis.exists("tidal_track::$trackId")) {
            RedisCacheObject.fromCache(jedis.get("tidal_track::$trackId"))
        } else null
    }
}

fun TidalService.checkExistingTracksFromCache(trackIds: List<String>): List<String> {
    if (jedis == null) return listOf()

    val existing = mutableListOf<String>()
    for (trackId in trackIds) {
        if (getTrackFromJedis(trackId) != null) existing.add(trackId)
    }
    return existing
}

fun TidalService.getTracksFromCache(trackIds: List<String>): List<IMetadataService.Track> {
    if (jedis == null) return listOf()

    val tracks = mutableListOf<IMetadataService.Track?>()
    for (trackId in trackIds) {
        tracks.add(getTrackFromJedis(trackId))
    }
    return tracks.filterNotNull()
}

// ALBUMS

fun TidalService.writeToJedis(album: IMetadataService.Album) {
    jedis?.psetex(
        "tidal_album::${album.id}",
        30.days.inWholeMilliseconds,
        RedisCacheObject.fromObject(album).toString()
    )
}

fun TidalService.getAlbumFromJedis(albumId: String): IMetadataService.Album? {
    return jedis?.let { jedis ->
        if (jedis.exists("tidal_album::$albumId")) {
            RedisCacheObject.fromCache(jedis.get("tidal_album::$albumId"))
        } else null
    }
}

fun TidalService.checkExistingAlbumsFromCache(albumIds: List<String>): List<String> {
    if (jedis == null) return listOf()

    val existing = mutableListOf<String>()
    for (albumId in albumIds) {
        if (getAlbumFromJedis(albumId) != null) existing.add(albumId)
    }
    return existing
}

fun TidalService.getAlbumsFromCache(albumIds: List<String>): List<IMetadataService.Album> {
    if (jedis == null) return listOf()

    val albums = mutableListOf<IMetadataService.Album?>()
    for (albumId in albumIds) {
        albums.add(getAlbumFromJedis(albumId))
    }
    return albums.filterNotNull()
}

// ARTISTS

fun TidalService.writeToJedis(artist: IMetadataService.Artist) {
    jedis?.psetex(
        "tidal_artist::${artist.id}",
        30.days.inWholeMilliseconds,
        RedisCacheObject.fromObject(artist).toString()
    )
}

fun TidalService.getArtistFromJedis(artistId: String): IMetadataService.Artist? {
    return jedis?.let { jedis ->
        if (jedis.exists("tidal_artist::$artistId")) {
            RedisCacheObject.fromCache(jedis.get("tidal_artist::$artistId"))
        } else null
    }
}

fun TidalService.checkExistingArtistsFromCache(artistIds: List<String>): List<String> {
    if (jedis == null) return listOf()

    val existing = mutableListOf<String>()
    for (artistId in artistIds) {
        if (getAlbumFromJedis(artistId) != null) existing.add(artistId)
    }
    return existing
}

fun TidalService.getArtistsFromCache(artistIds: List<String>): List<IMetadataService.Artist> {
    if (jedis == null) return listOf()

    val artists = mutableListOf<IMetadataService.Artist?>()
    for (artistId in artistIds) {
        artists.add(getArtistFromJedis(artistId))
    }
    return artists.filterNotNull()
}

// PLAYLISTS

fun TidalService.writeToJedis(playlist: IMetadataService.Playlist) {
    jedis?.psetex(
        "tidal_playlist::${playlist.id}",
        30.days.inWholeMilliseconds,
        RedisCacheObject.fromObject(playlist).toString()
    )
}

fun TidalService.getPlaylistFromJedis(playlistId: String): IMetadataService.Playlist? {
    return jedis?.let { jedis ->
        if (jedis.exists("tidal_playlist::$playlistId")) {
            RedisCacheObject.fromCache(jedis.get("tidal_playlist::$playlistId"))
        } else null
    }
}

fun TidalService.checkExistingPlaylistsFromCache(playlistIds: List<String>): List<String> {
    if (jedis == null) return listOf()

    val existing = mutableListOf<String>()
    for (playlistId in playlistIds) {
        if (getPlaylistFromJedis(playlistId) != null) existing.add(playlistId)
    }
    return existing
}

fun TidalService.getPlaylistsFromCache(playlistIds: List<String>): List<IMetadataService.Playlist> {
    if (jedis == null) return listOf()

    val playlists = mutableListOf<IMetadataService.Playlist?>()
    for (playlistId in playlistIds) {
        playlists.add(getPlaylistFromJedis(playlistId))
    }
    return playlists.filterNotNull()
}