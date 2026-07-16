package dev.dertyp.services.subsonic

import dev.dertyp.data.User
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject
import java.util.UUID

internal const val SUBSONIC_AUTH_NOTE =
    "Authenticate with `u`+`t`+`s` (token), `u`+`p` (password, optionally `enc:`-hex), or an `apiKey` holding the `subsonic` scope. Select the response format with `f` (xml, json or jsonp). Also available as POST and with a `.view` suffix."

internal fun Route.sub(name: String, docs: RouteConfig.() -> Unit = {}, handler: suspend RoutingContext.(Parameters) -> Unit) {
    val impl: suspend RoutingContext.() -> Unit = {
        val form = if (call.request.local.method == HttpMethod.Post) {
            runCatching { call.receiveParameters() }.getOrNull()
        } else null
        val params = if (form == null || form.isEmpty()) call.request.queryParameters else Parameters.build {
            appendAll(call.request.queryParameters)
            form.forEach { key, values -> appendAll(key, values) }
        }
        handler(params)
    }
    get("/$name", {
        tags("Subsonic")
        docs()
        description = listOfNotNull(description, SUBSONIC_AUTH_NOTE).joinToString(" ")
    }) { impl() }
    post("/$name", { hidden = true }) { impl() }
    get("/$name.view", { hidden = true }) { impl() }
    post("/$name.view", { hidden = true }) { impl() }
}

internal fun Route.subAuth(
    name: String,
    authenticator: SubsonicAuthenticator,
    docs: RouteConfig.() -> Unit = {},
    handler: suspend RoutingContext.(Parameters, User) -> Unit,
) = sub(name, docs) { params ->
    when (val result = authenticator.authenticate(params)) {
        is SubsonicAuthResult.Ok -> handler(params, result.user)
        is SubsonicAuthResult.Failure ->
            call.respondSubsonic(subsonicError(result.code, result.message), params["f"], params["callback"])
    }
}

internal suspend fun RoutingContext.respondNotFound(params: Parameters, what: String) =
    call.respondSubsonic(subsonicError(70, "$what not found"), params["f"], params["callback"])

internal suspend fun RoutingContext.respondMissingParam(params: Parameters, name: String) =
    call.respondSubsonic(subsonicError(10, "Required parameter '$name' is missing"), params["f"], params["callback"])

internal fun <T : Any> List<T>.orderedBy(ids: List<UUID>, idOf: (T) -> UUID): List<T> {
    val byId = associateBy(idOf)
    return ids.mapNotNull { byId[it] }
}

fun Route.subsonicRouting() {
    route("/rest") {
        install(PartialContent) { maxRangeCount = 10 }
        subsonicSystemRoutes()
        subsonicBrowseRoutes()
        subsonicMediaRoutes()
        subsonicPlaylistRoutes()
        subsonicAnnotationRoutes()
    }
}

private fun Route.subsonicSystemRoutes() {
    val authenticator by inject<SubsonicAuthenticator>()

    subAuth("ping", authenticator, {
        summary = "Test connectivity"
        description = "Returns an empty ok response when the credentials are valid."
    }) { params, _ ->
        call.respondSubsonic(SubsonicResponse(), params["f"], params["callback"])
    }

    subAuth("getLicense", authenticator, {
        summary = "Get license details"
        description = "Synara always reports a valid license."
    }) { params, user ->
        call.respondSubsonic(
            SubsonicResponse(license = License(valid = true, email = user.username, licenseExpires = "2099-12-31T23:59:59Z")),
            params["f"], params["callback"],
        )
    }

    sub("getOpenSubsonicExtensions", {
        summary = "List supported OpenSubsonic extensions"
        description = "Available without authentication."
    }) { params ->
        call.respondSubsonic(
            SubsonicResponse(
                openSubsonicExtensions = listOf(
                    OpenSubsonicExtension("formPost", listOf(1)),
                    OpenSubsonicExtension("apiKeyAuthentication", listOf(1)),
                    OpenSubsonicExtension("songLyrics", listOf(1)),
                ),
            ),
            params["f"], params["callback"],
        )
    }
}

private fun Route.subsonicBrowseRoutes() {
    val authenticator by inject<SubsonicAuthenticator>()
    val queryService by inject<SubsonicQueryService>()
    val songService by inject<SongService>()
    val albumService by inject<AlbumService>()
    val artistService by inject<ArtistService>()

    subAuth("getMusicFolders", authenticator, {
        summary = "List top-level music folders"
        description = "Synara exposes a single virtual folder."
    }) { params, _ ->
        call.respondSubsonic(
            SubsonicResponse(musicFolders = MusicFolders(listOf(MusicFolder(1, "Music")))),
            params["f"], params["callback"],
        )
    }

    subAuth("getArtists", authenticator, {
        summary = "List all artists"
        description = "All artists in the library, bucketed by first letter (ID3 style)."
    }) { params, user ->
        val artists = artistService.allArtists(0, Int.MAX_VALUE, user.id).data
        val buckets = artists.groupBy {
            val first = it.name.firstOrNull()?.uppercaseChar()
            if (first != null && first.isLetter()) first.toString() else "#"
        }
        val indexes = buckets.entries.sortedBy { it.key }.map { (letter, bucket) ->
            IndexID3(name = letter, artist = bucket.sortedBy { it.name.lowercase() }.map { it.toArtistID3() })
        }
        call.respondSubsonic(
            SubsonicResponse(artists = ArtistsID3(index = indexes)),
            params["f"], params["callback"],
        )
    }

    subAuth("getArtist", authenticator, {
        summary = "Get an artist with albums"
        request { queryParameter<String>("id") { description = "Artist id (`ar-<uuid>`)." } }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"]) as? SubsonicId.Artist
            ?: return@subAuth respondNotFound(params, "Artist")
        val artist = artistService.byId(id.uuid, user.id)
            ?: return@subAuth respondNotFound(params, "Artist")
        val albums = albumService.byArtist(0, Int.MAX_VALUE, id.uuid, singles = false, userId = user.id).data +
                albumService.byArtist(0, Int.MAX_VALUE, id.uuid, singles = true, userId = user.id).data
        val stars = queryService.starredAlbumStars(user.id)
        call.respondSubsonic(
            SubsonicResponse(
                artist = ArtistWithAlbumsID3(
                    id = artist.id.arId(),
                    name = artist.name,
                    coverArt = artist.imageId?.imId(),
                    albumCount = albums.size,
                    starred = artist.toArtistID3().starred,
                    musicBrainzId = artist.musicbrainzId?.toString(),
                    album = albums.map { it.toAlbumID3(starredIso(stars[it.id])) },
                ),
            ),
            params["f"], params["callback"],
        )
    }

    subAuth("getAlbum", authenticator, {
        summary = "Get an album with songs"
        request { queryParameter<String>("id") { description = "Album id (`al-<uuid>`)." } }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"]) as? SubsonicId.Album
            ?: return@subAuth respondNotFound(params, "Album")
        val album = albumService.byId(id.uuid, user.id)
            ?: return@subAuth respondNotFound(params, "Album")
        val songs = songService.byAlbum(0, Int.MAX_VALUE, id.uuid, user.id).data
        val stars = queryService.starredAlbumStars(user.id)
        val base = album.toAlbumID3(starredIso(stars[album.id]))
        call.respondSubsonic(
            SubsonicResponse(
                album = AlbumWithSongsID3(
                    id = base.id,
                    name = base.name,
                    artist = base.artist,
                    artistId = base.artistId,
                    coverArt = base.coverArt,
                    songCount = base.songCount,
                    duration = base.duration,
                    created = base.created,
                    starred = base.starred,
                    year = base.year,
                    genre = base.genre,
                    musicBrainzId = base.musicBrainzId,
                    genres = base.genres,
                    song = songs.map { it.toChild() },
                ),
            ),
            params["f"], params["callback"],
        )
    }

    subAuth("getSong", authenticator, {
        summary = "Get a single song"
        request { queryParameter<String>("id") { description = "Song id (`tr-<uuid>`)." } }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"]) as? SubsonicId.Song
            ?: return@subAuth respondNotFound(params, "Song")
        val song = songService.byId(id.uuid, user.id)
            ?: return@subAuth respondNotFound(params, "Song")
        call.respondSubsonic(SubsonicResponse(song = song.toChild()), params["f"], params["callback"])
    }

    subAuth("getAlbumList2", authenticator, {
        summary = "List albums (ID3)"
        request {
            queryParameter<String>("type") {
                description = "One of newest, random, alphabeticalByName, alphabeticalByArtist, byGenre, byYear, frequent, recent, starred."
                required = true
            }
            queryParameter<Int>("size") { description = "Number of albums (max 500, default 10)." }
            queryParameter<Int>("offset") { description = "List offset." }
            queryParameter<String>("genre") { description = "Genre name, required for type=byGenre." }
            queryParameter<Int>("fromYear") { description = "Start year, required for type=byYear." }
            queryParameter<Int>("toYear") { description = "End year, required for type=byYear." }
        }
    }) { params, user ->
        val type = params["type"]?.let { AlbumListType.fromKey(it) }
            ?: return@subAuth respondMissingParam(params, "type")
        val size = (params["size"]?.toIntOrNull() ?: 10).coerceIn(1, 500)
        val offset = (params["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0)
        if (type == AlbumListType.BY_GENRE && params["genre"] == null) {
            return@subAuth respondMissingParam(params, "genre")
        }
        if (type == AlbumListType.BY_YEAR && (params["fromYear"] == null || params["toYear"] == null)) {
            return@subAuth respondMissingParam(params, "fromYear")
        }
        val ids = queryService.albumIds(
            type, size, offset,
            genre = params["genre"],
            fromYear = params["fromYear"]?.toIntOrNull(),
            toYear = params["toYear"]?.toIntOrNull(),
            userId = user.id,
        )
        val stars = queryService.starredAlbumStars(user.id)
        val albums = albumService.byIds(ids, user.id).orderedBy(ids) { it.id }
        call.respondSubsonic(
            SubsonicResponse(albumList2 = AlbumList2(albums.map { it.toAlbumID3(starredIso(stars[it.id])) })),
            params["f"], params["callback"],
        )
    }

    subAuth("getGenres", authenticator, {
        summary = "List genres with song and album counts"
    }) { params, _ ->
        call.respondSubsonic(
            SubsonicResponse(genres = Genres(queryService.genresWithCounts())),
            params["f"], params["callback"],
        )
    }

    subAuth("getSongsByGenre", authenticator, {
        summary = "List songs in a genre"
        request {
            queryParameter<String>("genre") { description = "Genre name."; required = true }
            queryParameter<Int>("count") { description = "Number of songs (max 500, default 10)." }
            queryParameter<Int>("offset") { description = "List offset." }
        }
    }) { params, user ->
        val genre = params["genre"] ?: return@subAuth respondMissingParam(params, "genre")
        val count = (params["count"]?.toIntOrNull() ?: 10).coerceIn(1, 500)
        val offset = (params["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0)
        val ids = queryService.songIdsByGenre(genre, count, offset)
        val songs = songService.byIds(ids, user.id).orderedBy(ids) { it.id }
        call.respondSubsonic(
            SubsonicResponse(songsByGenre = Songs(songs.map { it.toChild() })),
            params["f"], params["callback"],
        )
    }

    subAuth("getRandomSongs", authenticator, {
        summary = "Get random songs"
        request {
            queryParameter<Int>("size") { description = "Number of songs (max 500, default 10)." }
            queryParameter<String>("genre") { description = "Only songs in this genre." }
            queryParameter<Int>("fromYear") { description = "Only songs released this year or later." }
            queryParameter<Int>("toYear") { description = "Only songs released this year or earlier." }
        }
    }) { params, user ->
        val size = (params["size"]?.toIntOrNull() ?: 10).coerceIn(1, 500)
        val ids = queryService.randomSongIds(
            size,
            genre = params["genre"],
            fromYear = params["fromYear"]?.toIntOrNull(),
            toYear = params["toYear"]?.toIntOrNull(),
        )
        val songs = songService.byIds(ids, user.id)
        call.respondSubsonic(
            SubsonicResponse(randomSongs = Songs(songs.map { it.toChild() })),
            params["f"], params["callback"],
        )
    }

    subAuth("search3", authenticator, {
        summary = "Search artists, albums and songs"
        description = "Ranked full-text search; an empty query returns full listings for client sync."
        request {
            queryParameter<String>("query") { description = "Search query."; required = true }
            queryParameter<Int>("artistCount") { description = "Max artists (default 20)." }
            queryParameter<Int>("artistOffset") { description = "Artist result offset." }
            queryParameter<Int>("albumCount") { description = "Max albums (default 20)." }
            queryParameter<Int>("albumOffset") { description = "Album result offset." }
            queryParameter<Int>("songCount") { description = "Max songs (default 20)." }
            queryParameter<Int>("songOffset") { description = "Song result offset." }
        }
    }) { params, user ->
        val query = (params["query"] ?: "").trim().removeSurrounding("\"")
        val artistCount = (params["artistCount"]?.toIntOrNull() ?: 20).coerceIn(0, 500)
        val artistOffset = params["artistOffset"]?.toIntOrNull() ?: 0
        val albumCount = (params["albumCount"]?.toIntOrNull() ?: 20).coerceIn(0, 500)
        val albumOffset = params["albumOffset"]?.toIntOrNull() ?: 0
        val songCount = (params["songCount"]?.toIntOrNull() ?: 20).coerceIn(0, 500)
        val songOffset = params["songOffset"]?.toIntOrNull() ?: 0

        fun page(offset: Int, count: Int) = if (count > 0) offset / count else 0

        val artists = when {
            artistCount == 0 -> emptyList()
            query.isEmpty() -> artistService.allArtists(page(artistOffset, artistCount), artistCount, user.id).data
            else -> artistService.rankedSearch(page(artistOffset, artistCount), artistCount, query, user.id).data
        }
        val albums = when {
            albumCount == 0 -> emptyList()
            query.isEmpty() -> albumService.allAlbums(page(albumOffset, albumCount), albumCount, user.id).data
            else -> albumService.rankedSearch(page(albumOffset, albumCount), albumCount, query, user.id).data
        }
        val songs = when {
            songCount == 0 -> emptyList()
            query.isEmpty() -> songService.allSongs(page(songOffset, songCount), songCount, true, user.id).data
            else -> songService.rankedSearch(page(songOffset, songCount), songCount, query, true, user.id).data
        }
        val stars = queryService.starredAlbumStars(user.id)
        call.respondSubsonic(
            SubsonicResponse(
                searchResult3 = SearchResult3(
                    artist = artists.map { it.toArtistID3() },
                    album = albums.map { it.toAlbumID3(starredIso(stars[it.id])) },
                    song = songs.map { it.toChild() },
                ),
            ),
            params["f"], params["callback"],
        )
    }
}

internal fun starredIso(createdAt: Long?): String? =
    createdAt?.let { java.time.Instant.ofEpochMilli(it).toString() }
