package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.EntityType
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.MusicBrainzMedia
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.data.MusicBrainzTrack
import dev.dertyp.db.AlbumProviderTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AlbumVersionGroupTable
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.db.SongMusicBrainzTable
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.created
import dev.dertyp.testing.deleted
import dev.dertyp.testing.members
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedScopes
import dev.dertyp.testing.updated
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDate
import java.util.UUID

class AlbumEntityChangeTest : EntityChangeLibraryTest() {
    private fun release(): UUID = db {
        val release = UUID.randomUUID()
        MBReleaseTable.insert {
            it[id] = release
            it[title] = "Release"
        }
        release
    }

    private fun track(name: String, song: UUID, number: Int? = null): MusicBrainzTrack {
        val recording = UUID.randomUUID()
        db {
            MBRecordingTable.insert {
                it[id] = recording
                it[title] = name
            }
            SongMusicBrainzTable.insert {
                it[songId] = song
                it[musicBrainzId] = recording
            }
        }
        return MusicBrainzTrack(
            id = UUID.randomUUID(),
            position = number,
            title = name,
            recording = MusicBrainzRecording(id = recording, title = name),
        )
    }

    private fun groupOf(album: UUID): UUID? = db {
        AlbumTable.selectAll().where { AlbumTable.id eq album }.single()[AlbumTable.versionGroupId]?.value
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting an album records it with its songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumArtist = artist("Album Artist")
        val performer = artist("Performer")
        val album = album("Album", albumArtist)
        val first = song(album, "First", performer)
        val second = song(album, "Second", track = 2)
        val untouched = song(album("Other"), "Untouched")

        assertTrue(albumService.deleteAlbums(listOf(album)))

        assertEquals(
            setOf(
                deleted(EntityType.SONG, first),
                deleted(EntityType.SONG, second),
                deleted(EntityType.ALBUM, album),
                members(EntityType.ARTIST, albumArtist),
                members(EntityType.ARTIST, performer),
            ),
            recordedChanges(database)
        )
        assertEquals(setOf(EntityType.ALBUM to album, EntityType.ARTIST to performer), recordedScopes(database, EntityType.SONG, first))
        assertEquals(setOf(EntityType.ARTIST to albumArtist), recordedScopes(database, EntityType.ALBUM, album))
        assertEquals(1, runBlocking { songService.byIds(listOf(untouched)).size })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting empty albums records them and marks their artists and collections`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumArtist = artist("Album Artist")
            val empty = album("Empty", albumArtist)
            val filled = album("Filled", albumArtist)
            song(filled, "Song")
            val collection = db {
                val holder = CollectionTable.insertAndGetId {
                    it[name] = "Collection"
                    it[creator] = owner
                }.value
                CollectionAlbumTable.insert {
                    it[collectionId] = holder
                    it[albumId] = empty
                }
                holder
            }

            assertEquals(1, albumService.deleteEmptyAlbums())

            assertEquals(
                setOf(
                    deleted(EntityType.ALBUM, empty),
                    members(EntityType.ARTIST, albumArtist),
                    members(EntityType.COLLECTION, collection),
                ),
                recordedChanges(database)
            )
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a MusicBrainz track sync records the songs whose numbers change and nothing when they match`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val album = album("Album")
        val one = song(album, "One", track = 1)
        val two = song(album, "Two", track = 2)
        val three = song(album, "Three", track = 3)
        val tracks = listOf(
            Triple(1, 2, track("One", one)),
            Triple(1, 1, track("Two", two)),
            Triple(1, 3, track("Three", three)),
        )

        albumService.syncSongsWithMusicBrainz(album, tracks)

        assertEquals(setOf(updated(EntityType.SONG, one), updated(EntityType.SONG, two)), recordedChanges(database))
        assertEquals(setOf(EntityType.ALBUM to album), recordedScopes(database, EntityType.SONG, one))
        assertEquals(3, runBlocking { songService.byId(three)!!.trackNumber })
        clearRecordedChanges(database)

        albumService.syncSongsWithMusicBrainz(album, tracks)

        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a MusicBrainz release sync records the album when its barcode or track count changes`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val album = album("Album", tracks = 1)
            val song = song(album, "One", track = 5)
            val release = release()
            val media = MusicBrainzMedia(trackCount = 2, tracks = listOf(track("One", song, number = 1)))
            val synced = MusicBrainzRelease(id = release, barcode = "0123456789012", media = listOf(media))
            coEvery { cachedMusicBrainzService.getRelease(release) } returns synced
            coEvery { cachedMusicBrainzService.getRelease(release, any()) } returns synced

            albumService.syncAlbumSongsWithMusicBrainz(album, release)

            assertEquals(setOf(updated(EntityType.ALBUM, album), updated(EntityType.SONG, song)), recordedChanges(database))
            clearRecordedChanges(database)

            albumService.syncAlbumSongsWithMusicBrainz(album, release)

            assertEquals(emptySet<Any>(), recordedChanges(database))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the MusicBrainz link of an album is recorded when it changes and a check stamp is not`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val album = album("Album")
            val release = release()

            albumService.updateMusicBrainzLastCheck(album)
            assertEquals(emptySet<Any>(), recordedChanges(database))

            albumService.setMusicBrainzId(album, release, triggerMerge = false, triggerSync = false)
            assertEquals(setOf(updated(EntityType.ALBUM, album)), recordedChanges(database))
            clearRecordedChanges(database)

            albumService.setMusicBrainzId(album, release, triggerMerge = false, triggerSync = false)
            albumService.updateMusicBrainzLastCheck(album)
            assertEquals(emptySet<Any>(), recordedChanges(database))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `provider links record the album once and an enrichment without links only stamps it`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val album = album("Album")

            albumService.enrichProviders(album)
            assertTrue(
                db { AlbumTable.selectAll().where { AlbumTable.id eq album }.single()[AlbumTable.lastProviderEnrichment] } > 0
            )
            assertEquals(emptySet<Any>(), recordedChanges(database))

            albumService.addProviderUrl(album, "https://tidal.com/album/42")
            assertEquals(setOf(updated(EntityType.ALBUM, album)), recordedChanges(database))
            clearRecordedChanges(database)

            albumService.addProviderUrl(album, "https://tidal.com/album/42")
            assertEquals(emptySet<Any>(), recordedChanges(database))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `writing a known provider link again keeps the time it was added`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = album("Album")
        fun addedAt() = db { AlbumProviderTable.selectAll().map { it[AlbumProviderTable.addedAt] } }

        albumService.addProviderUrl(album, "https://tidal.com/album/42")
        db { AlbumProviderTable.update { it[addedAt] = 1_000 } }

        albumService.addProviderUrl(album, "https://tidal.com/album/42")
        assertEquals(listOf(1_000L), addedAt())

        albumService.enrichProviders(album)
        assertEquals(listOf(1_000L), addedAt())

        albumService.upsertAlbum(
            albumService.byId(album)!!.copy(originalId = "https://tidal.com/album/42"),
            triggerMerge = false
        )
        assertEquals(listOf(1_000L), addedAt())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updating an album records its fields and marks the artists it leaves and joins`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val before = artist("Before")
            val after = artist("After")
            val album = album("Album", before)
            val current = albumService.byId(album)!!

            albumService.updateAlbum(current.copy(name = "Renamed"))
            assertEquals(setOf(updated(EntityType.ALBUM, album)), recordedChanges(database))
            assertEquals(setOf(EntityType.ARTIST to before), recordedScopes(database, EntityType.ALBUM, album))
            clearRecordedChanges(database)

            albumService.updateAlbum(albumService.byId(album)!!)
            assertEquals(emptySet<Any>(), recordedChanges(database))

            albumService.updateAlbum(
                albumService.byId(album)!!.copy(artists = listOf(ArtistCredit(id = after, name = "After", isGroup = false)))
            )
            assertEquals(
                setOf(
                    updated(EntityType.ALBUM, album),
                    members(EntityType.ARTIST, before),
                    members(EntityType.ARTIST, after),
                ),
                recordedChanges(database)
            )
            assertEquals(setOf(EntityType.ARTIST to after), recordedScopes(database, EntityType.ALBUM, album))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mirroring a new album records its creation`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumArtist = artist("Album Artist")
        val remote = albumService.byId(album("Template"))!!.copy(
            id = UUID.randomUUID(),
            name = "Mirrored",
            artists = listOf(ArtistCredit(id = albumArtist, name = "Album Artist", isGroup = false)),
            versionGroupId = null,
        )

        albumService.upsertAlbum(remote)

        assertEquals(
            setOf(created(EntityType.ALBUM, remote.id), members(EntityType.ARTIST, albumArtist)),
            recordedChanges(database)
        )
        assertEquals(setOf(EntityType.ARTIST to albumArtist), recordedScopes(database, EntityType.ALBUM, remote.id))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a bulk create that fills a barcode records the existing album`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val known = album("Known Album", artist("Known Artist"), tracks = 3, released = "2020-01-01")
        val incoming = InsertableAlbum(
            name = "Known Album",
            artists = listOf("Known Artist"),
            releaseDate = LocalDate.of(2020, 1, 1),
            songCount = 3,
            barcode = "0123456789012",
        )

        assertEquals(mapOf(incoming to known), albumService.getOrBulkCreate(listOf(incoming)))
        assertEquals(setOf(updated(EntityType.ALBUM, known)), recordedChanges(database))
        clearRecordedChanges(database)

        assertEquals(mapOf(incoming to known), albumService.getOrBulkCreate(listOf(incoming)))
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    private fun shareGroup(vararg albums: UUID) = db {
        val group = AlbumVersionGroupTable.insertAndGetId { }
        AlbumTable.update({ AlbumTable.id inList albums.toList() }) { it[versionGroupId] = group }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an album joining a version group records itself and the editions already in it`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumArtist = artist("Album Artist")
            val standard = album("Same Name", albumArtist)
            val deluxe = album("Same Name", albumArtist)
            val unrelated = album("Unrelated", albumArtist)
            val groupsBefore = listOf(standard, deluxe, unrelated).associateWith { groupOf(it) }

            assertEquals(1, albumService.rebuildVersionGroups())

            val moved = listOf(standard, deluxe, unrelated).filter { groupOf(it) != groupsBefore[it] }
            assertEquals(1, moved.size)
            assertTrue(moved.single() != unrelated)
            assertEquals(groupOf(standard), groupOf(deluxe))
            assertEquals(
                setOf(updated(EntityType.ALBUM, standard), updated(EntityType.ALBUM, deluxe)),
                recordedChanges(database)
            )
            clearRecordedChanges(database)

            assertEquals(0, albumService.rebuildVersionGroups())
            assertEquals(emptySet<Any>(), recordedChanges(database))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an album leaving a version group records itself and the editions that stay`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumArtist = artist("Album Artist")
            val standard = album("Same Name", albumArtist)
            val deluxe = album("Same Name", albumArtist)
            val stranger = album("Stranger", albumArtist)
            val unrelated = album("Unrelated", albumArtist)
            shareGroup(standard, deluxe, stranger)
            val shared = groupOf(standard)

            assertEquals(1, albumService.rebuildVersionGroups())

            assertEquals(shared, groupOf(standard))
            assertEquals(shared, groupOf(deluxe))
            assertTrue(groupOf(stranger) != shared)
            assertEquals(
                setOf(
                    updated(EntityType.ALBUM, standard),
                    updated(EntityType.ALBUM, deluxe),
                    updated(EntityType.ALBUM, stranger),
                ),
                recordedChanges(database)
            )
            assertTrue(updated(EntityType.ALBUM, unrelated) !in recordedChanges(database))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting one edition records the editions that stay in its version group`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumArtist = artist("Album Artist")
            val standard = album("Same Name", albumArtist)
            val deluxe = album("Same Name", albumArtist)
            val live = album("Same Name", albumArtist)
            val unrelated = album("Unrelated", albumArtist)
            shareGroup(standard, deluxe, live)
            for (filled in listOf(standard, live, unrelated)) song(filled, "Song")

            assertEquals(1, albumService.deleteEmptyAlbums())

            assertEquals(
                setOf(
                    deleted(EntityType.ALBUM, deluxe),
                    updated(EntityType.ALBUM, standard),
                    updated(EntityType.ALBUM, live),
                    members(EntityType.ARTIST, albumArtist),
                ),
                recordedChanges(database)
            )
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging one edition away records the editions that stay in its version group`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumArtist = artist("Album Artist")
            val kept = album("Kept", albumArtist)
            val removed = album("Same Name", albumArtist)
            val sibling = album("Same Name", albumArtist)
            val unrelated = album("Unrelated", albumArtist)
            shareGroup(removed, sibling)

            db { getKoin().get<EntityChangeRecorder>().merging(EntityType.ALBUM, kept, listOf(removed)) }

            val recorded = recordedChanges(database)
            assertTrue(deleted(EntityType.ALBUM, removed) in recorded)
            assertTrue(updated(EntityType.ALBUM, kept) in recorded)
            assertTrue(updated(EntityType.ALBUM, sibling) in recorded)
            assertTrue(updated(EntityType.ALBUM, unrelated) !in recorded)
        }

    private fun estimatedAlbum(albumName: String, estimatedDate: String): UUID {
        val album = album(albumName)
        db {
            AlbumTable.update({ AlbumTable.id eq album }) {
                it[releaseDate] = estimatedDate
                it[releaseDateEstimated] = true
            }
        }
        return album
    }

    private fun storedReleaseDate(album: UUID): Pair<String?, Boolean> = db {
        AlbumTable.selectAll().where { AlbumTable.id eq album }.single().let {
            it[AlbumTable.releaseDate] to it[AlbumTable.releaseDateEstimated]
        }
    }

    private fun changeRows(): Long = db { EntityChangeTable.selectAll().count() }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a release date filled later records the album once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val estimated = estimatedAlbum("Estimated", "2026-01-01")
        val dated = album("Dated", released = "2010-03-04")

        db {
            albumService.fillUnknownReleaseDatesTx(
                mapOf(estimated to LocalDate.of(2016, 5, 20), dated to LocalDate.of(2016, 5, 20))
            )
        }

        assertEquals("2016-05-20" to false, storedReleaseDate(estimated))
        assertEquals("2010-03-04" to false, storedReleaseDate(dated))
        assertEquals(setOf(updated(EntityType.ALBUM, estimated)), recordedChanges(database))
        assertEquals(1L, changeRows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `clearing only the estimated marker records nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val estimated = estimatedAlbum("Estimated", "2016-05-20")

        db { albumService.fillUnknownReleaseDatesTx(mapOf(estimated to LocalDate.of(2016, 5, 20))) }

        assertEquals("2016-05-20" to false, storedReleaseDate(estimated))
        assertEquals(emptySet<Any>(), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linking a release with a date records the album once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val estimated = estimatedAlbum("Estimated", "2026-01-01")
        val release = release()
        coEvery { cachedMusicBrainzService.getRelease(release, any()) } returns MusicBrainzRelease(
            id = release,
            title = "Release",
            date = "2016-05"
        )

        albumService.setMusicBrainzId(estimated, release, triggerMerge = false, triggerSync = false)

        assertEquals("2016-05-01" to false, storedReleaseDate(estimated))
        assertEquals(setOf(updated(EntityType.ALBUM, estimated)), recordedChanges(database))
        assertEquals(1L, changeRows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a song getting a release date fills its album and records both`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val estimated = estimatedAlbum("Estimated", "2026-01-01")
        val song = song(estimated, "Song")
        val recording = UUID.randomUUID()
        coEvery { cachedMusicBrainzService.getRecording(recording, any()) } returns MusicBrainzRecording(
            id = recording,
            title = "Song",
            releases = listOf(
                MusicBrainzRelease(id = UUID.randomUUID(), title = "Later", date = "2019-02-03"),
                MusicBrainzRelease(id = UUID.randomUUID(), title = "First", date = "2018")
            )
        )
        db {
            MBRecordingTable.insert {
                it[id] = recording
                it[title] = "Song"
            }
        }

        songService.setMusicBrainzId(song, recording, owner)

        assertEquals("2018-01-01" to false, storedReleaseDate(estimated))
        assertEquals(
            setOf(updated(EntityType.SONG, song), updated(EntityType.ALBUM, estimated)),
            recordedChanges(database)
        )
    }
}
