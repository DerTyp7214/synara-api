package dev.dertyp.services.subsonic

import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistService
import io.ktor.http.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

internal fun Route.subsonicPlaylistRoutes() {
    val authenticator by inject<SubsonicAuthenticator>()
    val queryService by inject<SubsonicQueryService>()
    val songService by inject<SongService>()
    val playlistService by inject<UserPlaylistService>()

    suspend fun playlistWithSongs(playlist: UserPlaylist, user: User): PlaylistWithSongs {
        val songs = songService.byUserPlaylist(0, Int.MAX_VALUE, playlist.id, user.id).data
        val durationMs = songs.sumOf { it.duration }
        val base = playlist.toPlaylistDto(user.username, songs.size, durationMs)
        return PlaylistWithSongs(
            id = base.id,
            name = base.name,
            comment = base.comment,
            owner = base.owner,
            public = base.public,
            songCount = songs.size,
            duration = base.duration,
            created = base.created,
            changed = base.changed,
            coverArt = base.coverArt,
            entry = songs.map { it.toChild() },
        )
    }

    fun ownedPlaylist(params: Parameters): SubsonicId.Playlist? =
        (SubsonicId.parse(params["id"] ?: params["playlistId"]) as? SubsonicId.Playlist)

    subAuth("getPlaylists", authenticator, {
        summary = "List the user's playlists"
    }) { params, user ->
        val playlists = playlistService.allPlaylists(user.id, 0, Int.MAX_VALUE).data
        call.respondSubsonic(
            SubsonicResponse(
                playlists = Playlists(
                    playlists.map {
                        it.toPlaylistDto(user.username, it.songs.size, it.totalDuration)
                    },
                ),
            ),
            params["f"], params["callback"],
        )
    }

    subAuth("getPlaylist", authenticator, {
        summary = "Get a playlist with songs"
        request { queryParameter<String>("id") { description = "Playlist id (`pl-<uuid>`)."; required = true } }
    }) { params, user ->
        val id = ownedPlaylist(params) ?: return@subAuth respondNotFound(params, "Playlist")
        val playlist = playlistService.byId(id.uuid)?.takeIf { it.creator == user.id || user.isAdmin }
            ?: return@subAuth respondNotFound(params, "Playlist")
        call.respondSubsonic(
            SubsonicResponse(playlist = playlistWithSongs(playlist, user)),
            params["f"], params["callback"],
        )
    }

    subAuth("createPlaylist", authenticator, {
        summary = "Create a playlist or replace its songs"
        request {
            queryParameter<String>("name") { description = "Name for a new playlist (required unless playlistId is given)." }
            queryParameter<String>("playlistId") { description = "Existing playlist id to overwrite (`pl-<uuid>`)." }
            queryParameter<String>("songId") { description = "Song id to include; repeatable." }
        }
    }) { params, user ->
        val songIds = params.getAll("songId")?.mapNotNull { (SubsonicId.parse(it) as? SubsonicId.Song)?.uuid } ?: emptyList()
        val existingId = SubsonicId.parse(params["playlistId"]) as? SubsonicId.Playlist

        val playlistId = if (existingId != null) {
            val playlist = playlistService.byId(existingId.uuid)?.takeIf { it.creator == user.id || user.isAdmin }
                ?: return@subAuth respondNotFound(params, "Playlist")
            if (playlist.songs.isNotEmpty()) playlistService.removeFromPlaylist(playlist.id, playlist.songs)
            playlist.id
        } else {
            val name = params["name"] ?: return@subAuth respondMissingParam(params, "name")
            playlistService.getOrAddPlaylist(user.id, null, InsertablePlaylist(name = name, origin = "subsonic"))
        }
        if (songIds.isNotEmpty()) playlistService.addSongsToPlaylist(playlistId, songIds)

        val playlist = playlistService.byId(playlistId)
            ?: return@subAuth respondNotFound(params, "Playlist")
        call.respondSubsonic(
            SubsonicResponse(playlist = playlistWithSongs(playlist, user)),
            params["f"], params["callback"],
        )
    }

    subAuth("updatePlaylist", authenticator, {
        summary = "Update a playlist"
        request {
            queryParameter<String>("playlistId") { description = "Playlist id (`pl-<uuid>`)."; required = true }
            queryParameter<String>("name") { description = "New name." }
            queryParameter<String>("comment") { description = "New description." }
            queryParameter<String>("songIdToAdd") { description = "Song id to append; repeatable." }
            queryParameter<Int>("songIndexToRemove") { description = "Zero-based index of a song to remove; repeatable." }
        }
    }) { params, user ->
        val id = SubsonicId.parse(params["playlistId"]) as? SubsonicId.Playlist
            ?: return@subAuth respondNotFound(params, "Playlist")
        val playlist = playlistService.byId(id.uuid)?.takeIf { it.creator == user.id || user.isAdmin }
            ?: return@subAuth respondNotFound(params, "Playlist")

        queryService.updatePlaylistMeta(playlist.id, params["name"], params["comment"])

        val toAdd = params.getAll("songIdToAdd")?.mapNotNull { (SubsonicId.parse(it) as? SubsonicId.Song)?.uuid } ?: emptyList()
        if (toAdd.isNotEmpty()) playlistService.addSongsToPlaylist(playlist.id, toAdd)

        val indexesToRemove = params.getAll("songIndexToRemove")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        if (indexesToRemove.isNotEmpty()) {
            val ordered = songService.byUserPlaylist(0, Int.MAX_VALUE, playlist.id, user.id).data
            val removeIds = indexesToRemove.mapNotNull { ordered.getOrNull(it)?.id }
            if (removeIds.isNotEmpty()) playlistService.removeFromPlaylist(playlist.id, removeIds)
        }

        call.respondSubsonic(SubsonicResponse(), params["f"], params["callback"])
    }

    subAuth("deletePlaylist", authenticator, {
        summary = "Delete a playlist"
        request { queryParameter<String>("id") { description = "Playlist id (`pl-<uuid>`)."; required = true } }
    }) { params, user ->
        val id = ownedPlaylist(params) ?: return@subAuth respondNotFound(params, "Playlist")
        val playlist = playlistService.byId(id.uuid)?.takeIf { it.creator == user.id || user.isAdmin }
            ?: return@subAuth respondNotFound(params, "Playlist")
        playlistService.delete(playlist.id)
        call.respondSubsonic(SubsonicResponse(), params["f"], params["callback"])
    }
}
