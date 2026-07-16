package dev.dertyp.services.subsonic

import dev.dertyp.data.User
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.DiscoveryService
import dev.dertyp.services.ListenService
import dev.dertyp.services.LyricsService
import dev.dertyp.services.ScrobbleService
import dev.dertyp.services.SongService
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject

internal fun Route.subsonicAnnotationRoutes() {
    val authenticator by inject<SubsonicAuthenticator>()
    val queryService by inject<SubsonicQueryService>()
    val songService by inject<SongService>()
    val albumService by inject<AlbumService>()
    val artistService by inject<ArtistService>()
    val listenService by inject<ListenService>()
    val scrobbleService by inject<ScrobbleService>()
    val discoveryService by inject<DiscoveryService>()
    val lyricsService by inject<LyricsService>()

    suspend fun setStars(params: Parameters, user: User, starred: Boolean) {
        val ids = listOf("id", "albumId", "artistId")
            .flatMap { params.getAll(it) ?: emptyList() }
            .mapNotNull { SubsonicId.parse(it) }
        for (id in ids) {
            when (id) {
                is SubsonicId.Song -> songService.setLiked(id.uuid, user.id, starred, null)
                is SubsonicId.Album -> queryService.setAlbumStar(user.id, id.uuid, starred)
                is SubsonicId.Artist -> queryService.setArtistStar(user.id, id.uuid, starred)
                else -> Unit
            }
        }
    }

    subAuth("star", authenticator, {
        summary = "Star songs, albums or artists"
        request {
            queryParameter<String>("id") { description = "Song id to star; repeatable." }
            queryParameter<String>("albumId") { description = "Album id to star; repeatable." }
            queryParameter<String>("artistId") { description = "Artist id to star (follows the artist); repeatable." }
        }
    }) { params, user ->
        setStars(params, user, true)
        call.respondSubsonic(SubsonicResponse(), params["f"], params["callback"])
    }

    subAuth("unstar", authenticator, {
        summary = "Unstar songs, albums or artists"
        request {
            queryParameter<String>("id") { description = "Song id to unstar; repeatable." }
            queryParameter<String>("albumId") { description = "Album id to unstar; repeatable." }
            queryParameter<String>("artistId") { description = "Artist id to unstar; repeatable." }
        }
    }) { params, user ->
        setStars(params, user, false)
        call.respondSubsonic(SubsonicResponse(), params["f"], params["callback"])
    }

    subAuth("getStarred2", authenticator, {
        summary = "List starred songs, albums and artists (ID3)"
    }) { params, user ->
        val songs = songService.likedSongs(0, Int.MAX_VALUE, true, user.id).data
        val albumStars = queryService.starredAlbumStars(user.id)
        val albums = albumService.byIds(albumStars.keys.toList(), user.id)
        val artistIds = queryService.starredArtistIds(user.id)
        val artists = artistService.byIds(artistIds, user.id)
        call.respondSubsonic(
            SubsonicResponse(
                starred2 = Starred2(
                    artist = artists.map { it.toArtistID3() },
                    album = albums.map { it.toAlbumID3(starredIso(albumStars[it.id])) },
                    song = songs.map { it.toChild() },
                ),
            ),
            params["f"], params["callback"],
        )
    }

    subAuth("scrobble", authenticator, {
        summary = "Scrobble a play or set now playing"
        request {
            queryParameter<String>("id") { description = "Song id; repeatable."; required = true }
            queryParameter<Long>("time") { description = "Play timestamp in ms since epoch; pairs with each id." }
            queryParameter<Boolean>("submission") { description = "true (default) records a listen, false sets now playing." }
        }
    }) { params, user ->
        val ids = params.getAll("id")?.mapNotNull { (SubsonicId.parse(it) as? SubsonicId.Song)?.uuid } ?: emptyList()
        if (ids.isEmpty()) return@subAuth respondMissingParam(params, "id")
        val submission = params["submission"]?.toBooleanStrictOrNull() ?: true
        val times = params.getAll("time")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        if (submission) {
            ids.forEachIndexed { index, songId ->
                listenService.ingestLocal(user.id, songId, times.getOrNull(index) ?: System.currentTimeMillis(), null)
            }
        } else {
            ids.firstOrNull()?.let { scrobbleService.setNowPlaying(user.id, it) }
        }
        call.respondSubsonic(SubsonicResponse(), params["f"], params["callback"])
    }

    subAuth("getUser", authenticator, {
        summary = "Get user details and roles"
        request { queryParameter<String>("username") { description = "Must be the authenticated user unless admin." } }
    }) { params, user ->
        val requested = params["username"]
        if (requested != null && !requested.equals(user.username, ignoreCase = true) && !user.isAdmin) {
            return@subAuth call.respondSubsonic(
                subsonicError(50, "User is not authorized for the given operation"),
                params["f"], params["callback"],
            )
        }
        call.respondSubsonic(
            SubsonicResponse(
                user = SubsonicUser(
                    username = user.username,
                    adminRole = user.isAdmin,
                    settingsRole = user.isAdmin,
                ),
            ),
            params["f"], params["callback"],
        )
    }

    subAuth("getLyrics", authenticator, {
        summary = "Search lyrics by artist and title"
        request {
            queryParameter<String>("artist") { description = "Artist name." }
            queryParameter<String>("title") { description = "Song title." }
        }
    }) { params, user ->
        val artist = params["artist"] ?: ""
        val title = params["title"] ?: ""
        val song = songService.rankedSearch(0, 1, "$artist $title".trim(), true, user.id).data.firstOrNull()
        val lyrics = song?.lyrics?.takeIf { it.isNotBlank() }
        call.respondSubsonic(
            SubsonicResponse(
                lyrics = Lyrics(
                    artist = song?.artists?.firstOrNull()?.name ?: artist.ifEmpty { null },
                    title = song?.title ?: title.ifEmpty { null },
                    value = lyrics ?: "",
                ),
            ),
            params["f"], params["callback"],
        )
    }

    subAuth("getLyricsBySongId", authenticator, {
        summary = "Get structured lyrics for a song (OpenSubsonic)"
        description = "Returns synced lyrics with per-line offsets when available, otherwise unsynced lines."
        request { queryParameter<String>("id") { description = "Song id (`tr-<uuid>`)."; required = true } }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"]) as? SubsonicId.Song
            ?: return@subAuth respondNotFound(params, "Song")
        val song = songService.byId(id.uuid, user.id)
            ?: return@subAuth respondNotFound(params, "Song")

        val displayArtist = song.artists.firstOrNull()?.name
        val synced = lyricsService.getSyncedLyrics(id.uuid)
        val structured = when {
            synced != null && synced.lines.isNotEmpty() -> StructuredLyrics(
                displayArtist = displayArtist,
                displayTitle = song.title,
                synced = true,
                line = synced.lines.map { line ->
                    LyricsLine(
                        start = line.startTime.inWholeMilliseconds,
                        value = line.words.joinToString(" ") { it.text },
                    )
                },
            )

            song.lyrics.isNotBlank() -> StructuredLyrics(
                displayArtist = displayArtist,
                displayTitle = song.title,
                synced = false,
                line = song.lyrics.lines().map { LyricsLine(value = it) },
            )

            else -> null
        }
        call.respondSubsonic(
            SubsonicResponse(lyricsList = LyricsList(structured?.let { listOf(it) } ?: emptyList())),
            params["f"], params["callback"],
        )
    }

    subAuth("getSimilarSongs2", authenticator, {
        summary = "Get similar songs for an artist (ID3)"
        description = "Seeds Synara's discovery engine with the artist's most played songs; also accepts a song id as seed."
        request {
            queryParameter<String>("id") { description = "Artist id (`ar-<uuid>`) or song id (`tr-<uuid>`)."; required = true }
            queryParameter<Int>("count") { description = "Max songs (default 50)." }
        }
    }) { params, user ->
        val count = (params["count"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        val seeds = when (val id = SubsonicId.parse(params["id"])) {
            is SubsonicId.Artist -> queryService.topSongIdsForArtist(id.uuid, 5)
                .ifEmpty { songService.byArtist(0, 5, id.uuid, user.id).data.map { it.id } }

            is SubsonicId.Song -> listOf(id.uuid)
            else -> return@subAuth respondNotFound(params, "Artist")
        }
        val similar = if (seeds.isEmpty()) emptyList() else discoveryService.getSimilarSongs(seeds, count, user.id)
        call.respondSubsonic(
            SubsonicResponse(similarSongs2 = Songs(similar.map { it.toChild() })),
            params["f"], params["callback"],
        )
    }
}
