package dev.dertyp.services.subsonic

import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.db.UserTable
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistPermission
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.UserService
import io.ktor.http.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.inList
import org.koin.ktor.ext.inject
import java.util.UUID

internal fun Route.subsonicPlaylistRoutes() {
    val authenticator by inject<SubsonicAuthenticator>()
    val queryService by inject<SubsonicQueryService>()
    val songService by inject<SongService>()
    val playlistService by inject<UserPlaylistService>()
    val userService by inject<UserService>()

    suspend fun permissionOf(playlistId: UUID, user: User): UserPlaylistPermission =
        playlistService.accessOf(playlistId, user.id)
            ?.let { if (user.isAdmin) UserPlaylistPermission.OWNER else it }
            ?: UserPlaylistPermission.NONE

    suspend fun playlistWithSongs(playlist: UserPlaylist, user: User): PlaylistWithSongs {
        val songs = songService.byUserPlaylist(0, Int.MAX_VALUE, playlist.id, user.id).data
        val durationMs = songs.sumOf { it.duration }
        val owner = if (playlist.creator == user.id) user.username else userService.findUserById(playlist.creator)?.username
        val base = playlist.toPlaylistDto(owner, songs.size, durationMs)
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
        summary = "List the user's own playlists, the playlists shared with the user and the public playlists"
    }) { params, user ->
        val playlists = playlistService.allPlaylists(null, 0, Int.MAX_VALUE, user.id).data
        val owners = userService
            .queryUser { where { UserTable.id inList playlists.map { it.creator }.distinct() } }
            .associate { it.id to it.username }
        call.respondSubsonic(
            SubsonicResponse(
                playlists = Playlists(
                    playlists.map {
                        it.toPlaylistDto(owners[it.creator], it.songs.size, it.totalDuration)
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
        val playlist = playlistService.byId(id.uuid, user.id.takeUnless { user.isAdmin })
            ?: return@subAuth respondNotFound(params, "Playlist")
        call.respondSubsonic(
            SubsonicResponse(playlist = playlistWithSongs(playlist, user)),
            params["f"], params["callback"],
        )
    }

    subAuth("createPlaylist", authenticator, {
        summary = "Create a playlist or replace its songs"
        request {
            queryParameter<String>("name") {
                description = "Name for a new playlist (required unless playlistId is given)."
            }
            queryParameter<String>("playlistId") { description = "Existing playlist id to overwrite (`pl-<uuid>`)." }
            queryParameter<String>("songId") { description = "Song id to include; repeatable." }
            queryParameter<Boolean>("public") { description = "Whether every user can see the playlist. Owner only." }
        }
    }) { params, user ->
        val songIds =
            params.getAll("songId")?.mapNotNull { (SubsonicId.parse(it) as? SubsonicId.Song)?.uuid } ?: emptyList()
        val existingId = SubsonicId.parse(params["playlistId"]) as? SubsonicId.Playlist
        val isPublic = params["public"]?.toBooleanStrictOrNull()

        val playlistId = if (existingId != null) {
            val access = permissionOf(existingId.uuid, user)
            if (!access.canRead) return@subAuth respondNotFound(params, "Playlist")
            if (!access.canWrite || (isPublic != null && access != UserPlaylistPermission.OWNER)) {
                return@subAuth respondNotAuthorized(params)
            }
            val playlist = playlistService.byId(existingId.uuid)
                ?: return@subAuth respondNotFound(params, "Playlist")
            if (playlist.songs.isNotEmpty()) playlistService.removeFromPlaylist(playlist.id, playlist.songs)
            playlist.id
        } else {
            val name = params["name"] ?: return@subAuth respondMissingParam(params, "name")
            playlistService.getOrAddPlaylist(user.id, null, InsertablePlaylist(name = name, origin = "subsonic"))
        }
        if (songIds.isNotEmpty()) playlistService.addSongsToPlaylist(playlistId, songIds)
        if (isPublic != null) playlistService.setPublic(playlistId, isPublic)

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
            queryParameter<Boolean>("public") { description = "Whether every user can see the playlist." }
            queryParameter<String>("songIdToAdd") { description = "Song id to append; repeatable." }
            queryParameter<Int>("songIndexToRemove") {
                description = "Zero-based index of a song to remove; repeatable."
            }
        }
    }) { params, user ->
        val id = SubsonicId.parse(params["playlistId"]) as? SubsonicId.Playlist
            ?: return@subAuth respondNotFound(params, "Playlist")
        val access = permissionOf(id.uuid, user)
        if (!access.canRead) return@subAuth respondNotFound(params, "Playlist")
        val playlist = playlistService.byId(id.uuid)
            ?: return@subAuth respondNotFound(params, "Playlist")

        val isPublic = params["public"]?.toBooleanStrictOrNull()
        val toAdd =
            params.getAll("songIdToAdd")?.mapNotNull { (SubsonicId.parse(it) as? SubsonicId.Song)?.uuid } ?: emptyList()
        val indexesToRemove = params.getAll("songIndexToRemove")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val changesDetails = params["name"] != null || params["comment"] != null || isPublic != null
        val changesSongs = toAdd.isNotEmpty() || indexesToRemove.isNotEmpty()
        if ((changesDetails && access != UserPlaylistPermission.OWNER) || (changesSongs && !access.canWrite)) {
            return@subAuth respondNotAuthorized(params)
        }

        queryService.updatePlaylistMeta(playlist.id, params["name"], params["comment"])
        if (isPublic != null) playlistService.setPublic(playlist.id, isPublic)

        if (toAdd.isNotEmpty()) playlistService.addSongsToPlaylist(playlist.id, toAdd)

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
        val access = permissionOf(id.uuid, user)
        if (!access.canRead) return@subAuth respondNotFound(params, "Playlist")
        if (access != UserPlaylistPermission.OWNER) return@subAuth respondNotAuthorized(params)
        playlistService.delete(id.uuid)
        call.respondSubsonic(SubsonicResponse(), params["f"], params["callback"])
    }
}
