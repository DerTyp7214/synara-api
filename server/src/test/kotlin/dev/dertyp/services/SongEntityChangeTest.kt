package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.EntityType
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableSong
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.SongProviderTable
import dev.dertyp.db.SongTable
import dev.dertyp.services.schedule.LrcLibWorker
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.created
import dev.dertyp.testing.deleted
import dev.dertyp.testing.members
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedScopes
import dev.dertyp.testing.updated
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.time.LocalDate
import java.util.UUID

class SongEntityChangeTest : EntityChangeLibraryTest() {
    private fun knownAlbum() = InsertableAlbum(
        name = "Known Album",
        artists = listOf("Known Artist"),
        releaseDate = LocalDate.of(2020, 1, 1),
        songCount = 2,
    )

    private fun insertable(
        name: String,
        album: InsertableAlbum,
        performers: List<String>,
        explicit: Boolean = false,
        atmosPath: String? = null,
    ) = InsertableSong(
        title = name,
        artists = performers,
        album = album,
        duration = 1000,
        explicit = explicit,
        path = "/music/$name.flac",
        atmosPath = atmosPath,
        atmos = atmosPath?.let { AudioInfo("eac3", 48000, 0, 768, 10, 6) },
    )

    private fun credit(id: UUID, name: String) = ArtistCredit(id = id, name = name, isGroup = false)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `bulk create records only the rows it inserted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val knownArtist = artist("Known Artist")
        val knownAlbum = album("Known Album", knownArtist, tracks = 2, released = "2020-01-01")
        val freshAlbum = InsertableAlbum(
            name = "Fresh Album",
            artists = listOf("Fresh Artist"),
            releaseDate = LocalDate.of(2021, 1, 1),
            songCount = 1,
        )

        val result = songService.createBatch(
            listOf(
                insertable("First", knownAlbum(), listOf("Known Artist")),
                insertable("Second", freshAlbum, listOf("Fresh Artist")),
            )
        )

        val first = result.values.single { it.title == "First" }
        val second = result.values.single { it.title == "Second" }
        val freshAlbumId = second.album!!.id
        val freshArtistId = second.artists.single().id
        assertEquals(knownAlbum, first.album!!.id)
        assertEquals(
            setOf(
                created(EntityType.SONG, first.id),
                created(EntityType.SONG, second.id),
                created(EntityType.ALBUM, freshAlbumId),
                created(EntityType.ARTIST, freshArtistId),
                members(EntityType.ALBUM, knownAlbum),
                members(EntityType.ALBUM, freshAlbumId),
                members(EntityType.ARTIST, knownArtist),
                members(EntityType.ARTIST, freshArtistId),
            ),
            recordedChanges(database)
        )
        assertEquals(
            setOf(EntityType.ALBUM to knownAlbum, EntityType.ARTIST to knownArtist),
            recordedScopes(database, EntityType.SONG, first.id)
        )
        assertEquals(
            setOf(EntityType.ALBUM to freshAlbumId, EntityType.ARTIST to freshArtistId),
            recordedScopes(database, EntityType.SONG, second.id)
        )
        assertEquals(setOf(EntityType.ARTIST to freshArtistId), recordedScopes(database, EntityType.ALBUM, freshAlbumId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the album cleanup of a bulk create records the albums it removes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumArtist = artist("Album Artist")
        val empty = album("Empty", albumArtist, released = "2019-01-01")
        album("Known Album", artist("Known Artist"), tracks = 2, released = "2020-01-01")

        songService.createBatch(listOf(insertable("First", knownAlbum(), listOf("Known Artist"))))

        assertTrue(deleted(EntityType.ALBUM, empty) in recordedChanges(database))
        assertTrue(members(EntityType.ARTIST, albumArtist) in recordedChanges(database))
        assertEquals(setOf(EntityType.ARTIST to albumArtist), recordedScopes(database, EntityType.ALBUM, empty))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `indexing an unchanged song again records nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        album("Known Album", artist("Known Artist"), tracks = 2, released = "2020-01-01")
        val songs = listOf(insertable("First", knownAlbum(), listOf("Known Artist")))
        songService.createBatch(songs)
        clearRecordedChanges(database)

        val result = songService.createBatch(songs)

        assertTrue(result.isEmpty())
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `indexing an existing song with changed tags or a new variant records an update`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            album("Known Album", artist("Known Artist"), tracks = 2, released = "2020-01-01")
            val song = songService
                .createBatch(listOf(insertable("First", knownAlbum(), listOf("Known Artist"))))
                .keys.single()
            clearRecordedChanges(database)

            songService.createBatch(listOf(insertable("First", knownAlbum(), listOf("Known Artist"), explicit = true)))
            assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
            clearRecordedChanges(database)

            val withVariant =
                insertable("First", knownAlbum(), listOf("Known Artist"), explicit = true, atmosPath = "/music/a.ec3")
            songService.createBatch(listOf(withVariant))
            assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
            clearRecordedChanges(database)

            songService.createBatch(listOf(withVariant))
            assertEquals(emptySet<Any>(), recordedChanges(database))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updating song fields records the song only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val performer = artist("Performer")
        val album = album("Album", performer)
        val song = song(album, "Title", performer)
        val current = songService.byId(song)!!

        songService.updateSong(current.copy(title = "Renamed", trackNumber = 4), owner)

        assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
        assertEquals(setOf(EntityType.ALBUM to album, EntityType.ARTIST to performer), recordedScopes(database, EntityType.SONG, song))
        clearRecordedChanges(database)

        songService.updateSong(songService.byId(song)!!, owner)
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `changing the artists of a song marks the old and the new artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val before = artist("Before")
        val after = artist("After")
        val album = album("Album")
        val song = song(album, "Title", before)

        songService.updateSong(songService.byId(song)!!.copy(artists = listOf(credit(after, "After"))), owner)

        assertEquals(
            setOf(
                updated(EntityType.SONG, song),
                members(EntityType.ARTIST, before),
                members(EntityType.ARTIST, after),
            ),
            recordedChanges(database)
        )
        assertEquals(setOf(EntityType.ALBUM to album, EntityType.ARTIST to after), recordedScopes(database, EntityType.SONG, song))
        clearRecordedChanges(database)

        songService.setArtists(song, listOf(before, after), owner)

        assertEquals(setOf(updated(EntityType.SONG, song), members(EntityType.ARTIST, before)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving a song to another album marks both albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val source = album("Source")
        val target = album("Target")
        val song = song(source, "Title")

        songService.updateSong(songService.byId(song)!!.copy(album = albumService.byId(target)), owner)

        assertEquals(
            setOf(
                updated(EntityType.SONG, song),
                members(EntityType.ALBUM, source),
                members(EntityType.ALBUM, target),
            ),
            recordedChanges(database)
        )
        assertEquals(setOf(EntityType.ALBUM to target), recordedScopes(database, EntityType.SONG, song))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a recorded song keeps its scopes over field updates and gets new ones when it moves`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val performer = artist("Performer")
            val source = album("Source")
            val target = album("Target")
            val song = song(source, "Title", performer)

            songService.updateSong(songService.byId(song)!!.copy(title = "Renamed"), owner)
            val recordedIn = setOf(EntityType.ALBUM to source, EntityType.ARTIST to performer)
            assertEquals(recordedIn, recordedScopes(database, EntityType.SONG, song))

            songService.updateSong(songService.byId(song)!!.copy(title = "Renamed again"), owner)
            assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
            assertEquals(recordedIn, recordedScopes(database, EntityType.SONG, song))

            songService.updateSong(songService.byId(song)!!.copy(album = albumService.byId(target)), owner)
            assertEquals(
                setOf(
                    updated(EntityType.SONG, song),
                    members(EntityType.ALBUM, source),
                    members(EntityType.ALBUM, target),
                ),
                recordedChanges(database)
            )
            assertEquals(
                setOf(EntityType.ALBUM to target, EntityType.ARTIST to performer),
                recordedScopes(database, EntityType.SONG, song)
            )
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a song marks its containers and records the orphaned album`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val performer = artist("Performer")
        val albumArtist = artist("Album Artist")
        val shared = album("Shared", albumArtist)
        val single = album("Single", albumArtist)
        val kept = song(shared, "Kept", performer)
        val removed = song(shared, "Removed", performer, track = 2)
        val last = song(single, "Last", performer)

        assertTrue(songService.deleteSongs(listOf(removed)))

        assertEquals(
            setOf(
                deleted(EntityType.SONG, removed),
                members(EntityType.ALBUM, shared),
                members(EntityType.ARTIST, performer),
            ),
            recordedChanges(database)
        )
        assertEquals(setOf(EntityType.ALBUM to shared, EntityType.ARTIST to performer), recordedScopes(database, EntityType.SONG, removed))
        clearRecordedChanges(database)

        assertTrue(songService.deleteSongs(listOf(last)))

        assertEquals(
            setOf(
                deleted(EntityType.SONG, last),
                deleted(EntityType.ALBUM, single),
                members(EntityType.ARTIST, performer),
                members(EntityType.ARTIST, albumArtist),
            ),
            recordedChanges(database)
        )
        assertEquals(setOf(EntityType.ARTIST to albumArtist), recordedScopes(database, EntityType.ALBUM, single))
        assertEquals(1, db { SongTable.selectAll().where { SongTable.id eq kept }.count() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `lyrics, provider links, paths and the MusicBrainz link record the song once per real change`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val song = song(album("Album"), "Title", location = "/old/title.flac")
        val recording = UUID.randomUUID()
        db {
            MBRecordingTable.insert {
                it[id] = recording
                it[title] = "Title"
            }
        }

        songService.setLyrics(song, owner, listOf("line one", "line two"))
        assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
        clearRecordedChanges(database)

        songService.addProviderUrl(song, "https://tidal.com/track/123")
        assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
        clearRecordedChanges(database)
        songService.addProviderUrl(song, "https://tidal.com/track/123")
        assertEquals(emptySet<Any>(), recordedChanges(database))

        assertEquals(1, songService.moveSongs("/old", "/new"))
        assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
        clearRecordedChanges(database)

        songService.setMusicBrainzId(song, recording, owner)
        assertEquals(setOf(updated(EntityType.SONG, song)), recordedChanges(database))
        clearRecordedChanges(database)
        songService.setMusicBrainzId(song, recording, owner)
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    private fun locations() = db { SongTable.selectAll().associate { it[SongTable.id].value to it[SongTable.filePath] } }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs replaces the prefix at the start and records each matched song once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = album("Album")
        val direct = song(album, "Direct", location = "/old/direct.flac")
        val repeated = song(album, "Repeated", location = "/old/sub/old/repeated.flac")
        val exact = song(album, "Exact", location = "/old")
        val longer = song(album, "Longer", location = "/oldish/longer.flac")
        val elsewhere = song(album, "Elsewhere", location = "/other/old/elsewhere.flac")
        val relative = song(album, "Relative", location = "old/relative.flac")
        val pathless = song(album, "Pathless", location = "")

        assertEquals(4, songService.moveSongs("/old", "/new/er"))

        assertEquals(
            mapOf(
                direct to "/new/er/direct.flac",
                repeated to "/new/er/sub/old/repeated.flac",
                exact to "/new/er",
                longer to "/new/erish/longer.flac",
                elsewhere to "/other/old/elsewhere.flac",
                relative to "old/relative.flac",
                pathless to "",
            ),
            locations()
        )
        assertEquals(
            setOf(
                updated(EntityType.SONG, direct),
                updated(EntityType.SONG, repeated),
                updated(EntityType.SONG, exact),
                updated(EntityType.SONG, longer),
            ),
            recordedChanges(database)
        )
        assertEquals(setOf(EntityType.ALBUM to album), recordedScopes(database, EntityType.SONG, direct))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs keeps a trailing separator of the prefix and an empty prefix prepends`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = album("Album")
        val inside = song(album, "Inside", location = "/old/inside.flac")
        val sibling = song(album, "Sibling", location = "/older/sibling.flac")

        assertEquals(1, songService.moveSongs("/old/", "/new"))
        assertEquals(mapOf(inside to "/newinside.flac", sibling to "/older/sibling.flac"), locations())
        assertEquals(setOf(updated(EntityType.SONG, inside)), recordedChanges(database))
        clearRecordedChanges(database)

        assertEquals(2, songService.moveSongs("", "/mnt"))
        assertEquals(mapOf(inside to "/mnt/newinside.flac", sibling to "/mnt/older/sibling.flac"), locations())
        assertEquals(
            setOf(updated(EntityType.SONG, inside), updated(EntityType.SONG, sibling)),
            recordedChanges(database)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs matches case the way the database does and replaces the exact prefix only`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val album = album("Album")
        val lower = song(album, "Lower", location = "/old/lower.flac")
        val upper = song(album, "Upper", location = "/OLD/upper.flac")
        val mixed = song(album, "Mixed", location = "/OLD/sub/old/mixed.flac")

        val moved = songService.moveSongs("/old", "/new")

        if (dialect == DbDialect.SQLITE) {
            assertEquals(3, moved)
            assertEquals(
                mapOf(lower to "/new/lower.flac", upper to "/OLD/upper.flac", mixed to "/OLD/sub/new/mixed.flac"),
                locations()
            )
            assertEquals(
                setOf(
                    updated(EntityType.SONG, lower),
                    updated(EntityType.SONG, upper),
                    updated(EntityType.SONG, mixed),
                ),
                recordedChanges(database)
            )
        } else {
            assertEquals(1, moved)
            assertEquals(
                mapOf(lower to "/new/lower.flac", upper to "/OLD/upper.flac", mixed to "/OLD/sub/old/mixed.flac"),
                locations()
            )
            assertEquals(setOf(updated(EntityType.SONG, lower)), recordedChanges(database))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs with percent and underscore in the prefix moves the songs below that prefix`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val album = album("Album")
        val literal = song(album, "Literal", location = "/100%_lib/50%_off.flac")
        val wildcard = song(album, "Wildcard", location = "/100xylib/wildcard.flac")
        val later = song(album, "Later", location = "/100abclib/100%_lib/later.flac")
        val unrelated = song(album, "Unrelated", location = "/200%_lib/unrelated.flac")

        assertEquals(3, songService.moveSongs("/100%_lib", "/lib_%"))

        assertEquals(
            mapOf(
                literal to "/lib_%/50%_off.flac",
                wildcard to "/100xylib/wildcard.flac",
                later to "/100abclib/lib_%/later.flac",
                unrelated to "/200%_lib/unrelated.flac",
            ),
            locations()
        )
        assertEquals(
            setOf(
                updated(EntityType.SONG, literal),
                updated(EntityType.SONG, wildcard),
                updated(EntityType.SONG, later),
            ),
            recordedChanges(database)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs with a backslash in the prefix matches the way the database does`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = album("Album")
        val windows = song(album, "Windows", location = "C:\\music\\windows.flac")
        val slashless = song(album, "Slashless", location = "C:music/slashless.flac")

        val moved = songService.moveSongs("C:\\music", "D:\\media")

        if (dialect == DbDialect.SQLITE) {
            assertEquals(1, moved)
            assertEquals(
                mapOf(windows to "D:\\media\\windows.flac", slashless to "C:music/slashless.flac"),
                locations()
            )
            assertEquals(setOf(updated(EntityType.SONG, windows)), recordedChanges(database))
        } else {
            assertEquals(1, moved)
            assertEquals(
                mapOf(windows to "C:\\music\\windows.flac", slashless to "C:music/slashless.flac"),
                locations()
            )
            assertEquals(setOf(updated(EntityType.SONG, slashless)), recordedChanges(database))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs keeps paths outside the basic latin letters intact`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = album("Album")
        val oldPrefix = "/Müsik 😀🎧/日本 cafe\u0301"
        val newPrefix = "/Neu ß 🎵"
        val umlauts = song(album, "Umlauts", location = "$oldPrefix/Ünï çödé – Öl.flac")
        val emoji = song(album, "Emoji", location = "$oldPrefix/🎶 😀🎧 100% _live_.flac")
        val spaces = song(album, "Spaces", location = "$oldPrefix  two  spaces .flac")
        val composed = song(album, "Composed", location = "/Müsik 😀🎧/日本 café/composed.flac")
        val shorter = song(album, "Shorter", location = "/Müsik 😀/日本 cafe\u0301/shorter.flac")

        assertEquals(3, songService.moveSongs(oldPrefix, newPrefix))

        assertEquals(
            mapOf(
                umlauts to "$newPrefix/Ünï çödé – Öl.flac",
                emoji to "$newPrefix/🎶 😀🎧 100% _live_.flac",
                spaces to "$newPrefix  two  spaces .flac",
                composed to "/Müsik 😀🎧/日本 café/composed.flac",
                shorter to "/Müsik 😀/日本 cafe\u0301/shorter.flac",
            ),
            locations()
        )
        assertEquals(
            setOf(
                updated(EntityType.SONG, umlauts),
                updated(EntityType.SONG, emoji),
                updated(EntityType.SONG, spaces),
            ),
            recordedChanges(database)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs of albums with an original id prefix leaves the other albums alone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val imported = album("Imported")
        val other = album("Other")
        val local = album("Local")
        db {
            AlbumTable.update({ AlbumTable.id eq imported }) { it[originalId] = "tidal:123" }
            AlbumTable.update({ AlbumTable.id eq other }) { it[originalId] = "apple:123" }
        }
        val first = song(imported, "First", location = "/old/first.flac")
        val second = song(other, "Second", location = "/old/second.flac")
        val third = song(local, "Third", location = "/old/third.flac")
        val fourth = song(imported, "Fourth", location = "/elsewhere/fourth.flac")

        assertEquals(1, songService.moveSongs("/old", "/new", "tidal:"))

        assertEquals(
            mapOf(
                first to "/new/first.flac",
                second to "/old/second.flac",
                third to "/old/third.flac",
                fourth to "/elsewhere/fourth.flac",
            ),
            locations()
        )
        assertEquals(setOf(updated(EntityType.SONG, first)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moving songs without a match changes and records nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        assertEquals(0, songService.moveSongs("/old", "/new"))
        assertEquals(emptyMap<UUID, String>(), locations())
        assertEquals(emptySet<Any>(), recordedChanges(database))

        val song = song(album("Album"), "Title", location = "/music/title.flac")

        assertEquals(0, songService.moveSongs("/old", "/new"))
        assertEquals(0, songService.moveSongs("/music", "/new", "tidal:"))
        assertEquals(mapOf(song to "/music/title.flac"), locations())
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `writing a known provider link again keeps the time it was added`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val song = song(album("Album"), "Title")
        fun addedAt() = db { SongProviderTable.selectAll().map { it[SongProviderTable.addedAt] } }

        songService.addProviderUrl(song, "https://tidal.com/track/123")
        db { SongProviderTable.update { it[addedAt] = 1_000 } }
        clearRecordedChanges(database)

        songService.addProviderUrl(song, "https://tidal.com/track/123")
        assertEquals(listOf(1_000L), addedAt())

        songService.enrichProviders(song)
        assertEquals(listOf(1_000L), addedAt())
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `enriching providers without a new link only stamps the song`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val song = song(album("Album"), "Title")

        songService.enrichProviders(song)

        assertTrue(db { SongTable.selectAll().where { SongTable.id eq song }.single()[SongTable.lastProviderEnrichment] } > 0)
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mirroring a song records a creation, then only real changes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val performer = artist("Performer")
        val album = album("Album")
        val remote = songService.byId(song(album, "Template", performer))!!
            .copy(id = UUID.randomUUID(), title = "Mirrored", path = "/mirror/song.flac", album = albumService.byId(album))
        clearRecordedChanges(database)

        songService.upsertSong(remote)

        assertEquals(
            setOf(
                created(EntityType.SONG, remote.id),
                members(EntityType.ALBUM, album),
                members(EntityType.ARTIST, performer),
            ),
            recordedChanges(database)
        )
        clearRecordedChanges(database)

        songService.upsertSong(remote)
        assertEquals(emptySet<Any>(), recordedChanges(database))

        songService.upsertSong(remote.copy(title = "Mirrored again"))
        assertEquals(setOf(updated(EntityType.SONG, remote.id)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a changed duration or file size of a song marks its album for the totals`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val performer = artist("Performer")
        val album = album("Album")
        val current = songService.byId(song(album, "Title", performer))!!.copy(album = albumService.byId(album))
        songService.upsertSong(current)
        clearRecordedChanges(database)
        val stored = songService.byId(current.id)!!.copy(album = albumService.byId(album))

        songService.upsertSong(stored.copy(duration = stored.duration + 1000))
        assertEquals(
            setOf(updated(EntityType.SONG, current.id), members(EntityType.ALBUM, album)),
            recordedChanges(database)
        )
        clearRecordedChanges(database)

        db { SongTable.update({ SongTable.id eq current.id }) { it[fileSize] = 1 } }
        val before = db { entityStates(EntityType.SONG, listOf(current.id)) }
        db {
            SongTable.update({ SongTable.id eq current.id }) { it[fileSize] = 2 }
            getKoin().get<EntityEventPublisher>().recordChanges(before)
        }
        assertEquals(
            setOf(updated(EntityType.SONG, current.id), members(EntityType.ALBUM, album)),
            recordedChanges(database)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetched lyrics record the song and a lookup without a result only stamps it`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val lrcLibService = mockk<LrcLibService>()
            loadKoinModules(module { single { lrcLibService } })
            val found = song(album("Album"), "Found", artist("Performer"))
            val missing = song(album("Other"), "Missing")
            coEvery { lrcLibService.getLyrics(any(), "Found", any(), any()) } returns LrcLibResponse(
                id = 1,
                trackName = "Found",
                artistName = "Performer",
                albumName = "Album",
                duration = 1.0,
                instrumental = false,
                syncedLyrics = "[00:01.00] line",
            )
            coEvery { lrcLibService.getLyrics(any(), "Missing", any(), any()) } returns null

            val result = LrcLibWorker().run()

            assertEquals(1, result["synced"])
            assertEquals(1, result["notFound"])
            assertEquals(setOf(updated(EntityType.SONG, found)), recordedChanges(database))
            assertTrue(
                db { SongTable.selectAll().where { SongTable.id eq missing }.single()[SongTable.lastLyricsFetchAttempt] } > 0
            )
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failed artist change rolls its record back`(dialect: DbDialect) {
        setup(dialect)
        val performer = artist("Performer")
        val song = song(album("Album"), "Title", performer)

        assertThrows<Exception> {
            runBlocking { songService.setArtists(song, listOf(UUID.randomUUID()), owner) }
        }

        assertEquals(emptySet<Any>(), recordedChanges(database))
        assertEquals(setOf(performer), runBlocking { songService.byId(song)!!.artists.map { it.id }.toSet() })
    }
}
