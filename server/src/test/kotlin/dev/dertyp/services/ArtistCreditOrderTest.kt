package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.migrations.custom.BackfillArtistCreditOrder
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.testing.relaxedTaskLogService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class ArtistCreditOrderTest : KoinTest {
    private lateinit var database: Database
    private val artistService = mockk<ArtistService>(relaxed = true)
    private val cachedMusicBrainzService = mockk<CachedMusicBrainzService>(relaxed = true)

    private val highId = UUID.fromString("ffffffff-0000-0000-0000-000000000001")
    private val lowId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val highMbId = UUID.randomUUID()
    private val lowMbId = UUID.randomUUID()

    private val allTables = arrayOf(
        ArtistTable, AlbumTable, SongTable, SongVariantTable, SongTitleTagTable, SongArtistTable,
        SongMusicBrainzTable, SongAudioDataTable, ImageTable, GenreTable,
        UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
        ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
        PlaylistTable, UserSongTable, UserPlaylistTable,
        SongGenreTable, ArtistGenreTable, AlbumGenreTable,
        PlaylistSongTable, UserPlaylistSongTable,
        SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
        ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
        SongProviderTable, TimecodeTagTable,
        CollectionTable, CollectionSongTable, CollectionAlbumTable, CollectionArtistTable, CollectionPlaylistTable,
        *allMusicBrainzTables
    )

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "artist_credit_order")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        val logService = relaxedTaskLogService()
        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { cachedMusicBrainzService }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { artistService }
                single { mockk<GenreService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<PluginManager>(relaxed = true) }
                single<ScheduledTaskLogService> { logService }
                single { LibraryMergeService() }
                single { LibraryFileDeleter() }
                single { mockk<RedisSearchService>(relaxed = true) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertArtists() = transaction(database) {
        ArtistTable.insert { it[id] = highId; it[name] = "High" }
        ArtistTable.insert { it[id] = lowId; it[name] = "Low" }
        MBArtistTable.insert { it[id] = highMbId; it[name] = "High"; it[sortName] = "High" }
        MBArtistTable.insert { it[id] = lowMbId; it[name] = "Low"; it[sortName] = "Low" }
        ArtistMusicBrainzTable.insert { it[artistId] = highId; it[musicBrainzId] = highMbId }
        ArtistMusicBrainzTable.insert { it[artistId] = lowId; it[musicBrainzId] = lowMbId }
    }

    private fun credits(): List<MusicBrainzArtistCredit> = listOf(
        MusicBrainzArtistCredit(
            name = "High",
            joinphrase = " feat. ",
            artist = MusicBrainzArtist(id = highMbId, name = "High")
        ),
        MusicBrainzArtistCredit(name = "Low", joinphrase = "", artist = MusicBrainzArtist(id = lowMbId, name = "Low")),
    )

    private fun stubArtistLookup() {
        coEvery { artistService.byMusicBrainzIds(any(), any()) } returns listOf(
            Artist(id = highId, name = "High", isGroup = false, musicbrainzId = highMbId),
            Artist(id = lowId, name = "Low", isGroup = false, musicbrainzId = lowMbId),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `song credits from musicbrainz keep their order and join phrases`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertArtists()
        stubArtistLookup()

        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = songId; it[title] = "Duet"; it[this.albumId] = albumId }
            MBRecordingTable.insert { it[id] = recordingId; it[title] = "Duet" }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = recordingId }
        }
        coEvery { cachedMusicBrainzService.getRecording(recordingId, any()) } returns
                MusicBrainzRecording(id = recordingId, title = "Duet", artistCredit = credits())

        val songService = SongService()
        songService.fetchMusicBrainzId(songId, UUID.randomUUID())

        val song = songService.byId(songId)!!
        assertEquals(listOf(highId, lowId), song.artists.map { it.id })
        assertEquals(listOf(" feat. ", ""), song.artists.map { it.joinPhrase })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `album credits from musicbrainz keep their order and join phrases`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertArtists()
        stubArtistLookup()

        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = songId; it[title] = "Track"; it[this.albumId] = albumId }
            MBReleaseTable.insert { it[id] = releaseId; it[title] = "Album" }
            AlbumMusicBrainzTable.insert { it[this.albumId] = albumId; it[musicBrainzId] = releaseId }
        }
        coEvery { cachedMusicBrainzService.getRelease(releaseId, any()) } returns
                MusicBrainzRelease(id = releaseId, title = "Album", artistCredit = credits())

        val albumService = AlbumService()
        albumService.fetchMusicBrainzId(albumId, triggerMerge = false)

        val album = albumService.byId(albumId)!!
        assertEquals(listOf(highId, lowId), album.artists.map { it.id })
        assertEquals(listOf(" feat. ", ""), album.artists.map { it.joinPhrase })

        val song = SongService().byId(songId)!!
        assertEquals(listOf(highId, lowId), song.album!!.artists.map { it.id })
        assertEquals(listOf(" feat. ", ""), song.album!!.artists.map { it.joinPhrase })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backfilled musicbrainz credits survive song and album queries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertArtists()

        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = songId; it[title] = "Duet"; it[this.albumId] = albumId }
            MBRecordingTable.insert { it[id] = recordingId; it[title] = "Duet" }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = recordingId }
            MBReleaseTable.insert { it[id] = releaseId; it[title] = "Album" }
            AlbumMusicBrainzTable.insert { it[this.albumId] = albumId; it[musicBrainzId] = releaseId }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId; it[artistId] = highMbId; it[name] = "High"; it[joinPhrase] =
                " & "; it[position] = 0
            }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId; it[artistId] = lowMbId; it[name] = "Low"; it[joinPhrase] =
                ""; it[position] = 1
            }
            MBReleaseArtistCreditTable.insert {
                it[this.releaseId] = releaseId; it[artistId] = highMbId; it[name] = "High"; it[joinPhrase] =
                " x "; it[position] = 0
            }
            MBReleaseArtistCreditTable.insert {
                it[this.releaseId] = releaseId; it[artistId] = lowMbId; it[name] = "Low"; it[joinPhrase] =
                ""; it[position] = 1
            }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = lowId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = highId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = lowId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = highId }
        }

        BackfillArtistCreditOrder().migrate()

        val song = SongService().byId(songId)!!
        assertEquals(listOf(highId, lowId), song.artists.map { it.id })
        assertEquals(listOf(" & ", ""), song.artists.map { it.joinPhrase })
        assertEquals(listOf(highId, lowId), song.album!!.artists.map { it.id })
        assertEquals(listOf(" x ", ""), song.album!!.artists.map { it.joinPhrase })

        val album = AlbumService().byId(albumId)!!
        assertEquals(listOf(highId, lowId), album.artists.map { it.id })
        assertEquals(listOf(" x ", ""), album.artists.map { it.joinPhrase })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a partly matched credit keeps the credited main artist first`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertArtists()

        val unmatchedId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val secondUnmatchedId = UUID.fromString("11111111-0000-0000-0000-000000000000")
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        transaction(database) {
            ArtistTable.insert { it[id] = unmatchedId; it[name] = "Unmatched" }
            ArtistTable.insert { it[id] = secondUnmatchedId; it[name] = "Second Unmatched" }
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = songId; it[title] = "Song"; it[this.albumId] = albumId }
            MBRecordingTable.insert { it[id] = recordingId; it[title] = "Song" }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = recordingId }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId; it[artistId] = highMbId; it[name] = "High"; it[joinPhrase] =
                " feat. "; it[position] = 0
            }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId; it[artistId] = lowMbId; it[name] = "Low"; it[joinPhrase] =
                ""; it[position] = 1
            }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = secondUnmatchedId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = unmatchedId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = highId }
        }

        BackfillArtistCreditOrder().migrate()

        val song = SongService().byId(songId)!!
        assertEquals(listOf(highId, unmatchedId, secondUnmatchedId), song.artists.map { it.id })
        assertEquals(listOf(" feat. ", null, null), song.artists.map { it.joinPhrase })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `album genres are ordered by name`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val albumId = UUID.randomUUID()
        val rock = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val ambient = UUID.fromString("ffffffff-0000-0000-0000-00000000000b")
        val jazz = UUID.fromString("88888888-0000-0000-0000-00000000000c")
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            GenreTable.insert { it[id] = rock; it[name] = "Rock" }
            GenreTable.insert { it[id] = ambient; it[name] = "Ambient" }
            GenreTable.insert { it[id] = jazz; it[name] = "Jazz" }
            listOf(rock, ambient, jazz).forEach { genreId ->
                AlbumGenreTable.insert { it[this.albumId] = albumId; it[this.genreId] = genreId }
            }
        }

        val album = AlbumService().byId(albumId)!!
        assertEquals(listOf(ambient, jazz, rock), album.genres.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `artists without a credit position fall back to artist id order`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        transaction(database) {
            ArtistTable.insert { it[id] = highId; it[name] = "High" }
            ArtistTable.insert { it[id] = lowId; it[name] = "Low" }
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = songId; it[title] = "Song"; it[this.albumId] = albumId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = highId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = lowId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = highId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = lowId }
        }

        val song = SongService().byId(songId)!!
        assertEquals(listOf(lowId, highId), song.artists.map { it.id })
        assertEquals(listOf(null, null), song.artists.map { it.joinPhrase })
        assertEquals(listOf(lowId, highId), song.album!!.artists.map { it.id })

        val album = AlbumService().byId(albumId)!!
        assertEquals(listOf(lowId, highId), album.artists.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mirrored songs and albums keep the artist order and join phrases they arrive with`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            val albumId = UUID.randomUUID()
            val songId = UUID.randomUUID()
            transaction(database) {
                ArtistTable.insert { it[id] = highId; it[name] = "High" }
                ArtistTable.insert { it[id] = lowId; it[name] = "Low" }
                AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            }
            val artists = listOf(
                Artist(id = highId, name = "High", isGroup = false, joinPhrase = " with "),
                Artist(id = lowId, name = "Low", isGroup = false),
            )

            AlbumService().upsertAlbum(
                Album(
                    id = albumId,
                    name = "Album",
                    artists = artists,
                    releaseDate = null,
                    songCount = 1,
                    totalDuration = 1000
                )
            )
            val songService = SongService()
            songService.upsertSong(
                Song(
                    id = songId,
                    title = "Song",
                    artists = artists,
                    album = Album(
                        id = albumId,
                        name = "Album",
                        artists = artists,
                        releaseDate = null,
                        songCount = 1,
                        totalDuration = 1000
                    ),
                    duration = 1000,
                    explicit = false,
                    releaseDate = null,
                    lyrics = "",
                    path = "song.flac",
                    originalUrl = "",
                    trackNumber = 1,
                    discNumber = 1,
                    copyright = "",
                )
            )

            val song = songService.byId(songId)!!
            assertEquals(listOf(highId, lowId), song.artists.map { it.id })
            assertEquals(listOf(" with ", null), song.artists.map { it.joinPhrase })
            assertEquals(listOf(highId, lowId), song.album!!.artists.map { it.id })
            assertEquals(listOf(" with ", null), song.album!!.artists.map { it.joinPhrase })
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `song genres are ordered by name`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val rock = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val ambient = UUID.fromString("ffffffff-0000-0000-0000-00000000000b")
        val jazz = UUID.fromString("88888888-0000-0000-0000-00000000000c")
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = songId; it[title] = "Song"; it[this.albumId] = albumId }
            GenreTable.insert { it[id] = rock; it[name] = "Rock" }
            GenreTable.insert { it[id] = ambient; it[name] = "Ambient" }
            GenreTable.insert { it[id] = jazz; it[name] = "Jazz" }
            listOf(rock, ambient, jazz).forEach { genreId ->
                SongGenreTable.insert { it[this.songId] = songId; it[this.genreId] = genreId }
            }
        }

        val song = SongService().byId(songId)!!
        assertEquals(listOf(ambient, jazz, rock), song.genres.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `duplicate songs keep the oldest candidate`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val albumId = UUID.randomUUID()
        val oldest = UUID.fromString("ffffffff-0000-0000-0000-000000000001")
        val newer = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val explicitNewest = UUID.fromString("88888888-0000-0000-0000-000000000003")
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = oldest; it[title] = "Song"; it[this.albumId] = albumId; it[inserted] = 1000L }
            SongTable.insert { it[id] = newer; it[title] = "Song"; it[this.albumId] = albumId; it[inserted] = 2000L }
            SongTable.insert {
                it[id] = explicitNewest; it[title] = "Song"; it[this.albumId] = albumId; it[inserted] =
                3000L; it[explicit] = true
            }
        }

        val songService = SongService()
        val userId = UUID.randomUUID()
        assertEquals(listOf(oldest), songService.allSongs(0, 10, explicit = false, userId = userId).data.map { it.id })
        assertEquals(
            listOf(explicitNewest),
            songService.allSongs(0, 10, explicit = true, userId = userId).data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `duplicate songs inserted at the same time keep the lowest id`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val albumId = UUID.randomUUID()
        val high = UUID.fromString("ffffffff-0000-0000-0000-000000000001")
        val low = UUID.fromString("00000000-0000-0000-0000-000000000002")
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = high; it[title] = "Song"; it[this.albumId] = albumId; it[inserted] = 1000L }
            SongTable.insert { it[id] = low; it[title] = "Song"; it[this.albumId] = albumId; it[inserted] = 1000L }
        }

        val songService = SongService()
        val userId = UUID.randomUUID()
        assertEquals(listOf(low), songService.allSongs(0, 10, explicit = false, userId = userId).data.map { it.id })
        assertEquals(listOf(low), songService.allSongs(0, 10, explicit = true, userId = userId).data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `original url prefers the provider row of the stored url`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val albumId = UUID.randomUUID()
        val storedSong = UUID.randomUUID()
        val legacySong = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert {
                it[id] = storedSong; it[title] = "Stored"; it[this.albumId] = albumId; it[originalUrl] =
                "https://tidal/2"
            }
            SongTable.insert {
                it[id] = legacySong; it[title] = "Legacy"; it[this.albumId] = albumId; it[originalUrl] =
                "https://legacy/1"
            }
            SongProviderTable.insert {
                it[songId] = storedSong; it[provider] = "deezer"; it[externalId] = "1"; it[rawUrl] = "https://deezer/1"
            }
            SongProviderTable.insert {
                it[songId] = storedSong; it[provider] = "tidal"; it[externalId] = "2"; it[rawUrl] = "https://tidal/2"
            }
            SongProviderTable.insert {
                it[songId] = legacySong; it[provider] = "tidal"; it[externalId] = "9"; it[rawUrl] = "https://tidal/9"
            }
            SongProviderTable.insert {
                it[songId] = legacySong; it[provider] = "deezer"; it[externalId] = "7"; it[rawUrl] = "https://deezer/7"
            }
            SongProviderTable.insert {
                it[songId] = legacySong; it[provider] = "deezer"; it[externalId] = "5"; it[rawUrl] = "https://deezer/5"
            }
        }

        val songService = SongService()
        assertEquals("https://tidal/2", songService.byId(storedSong)!!.originalUrl)
        assertEquals("https://deezer/5", songService.byId(legacySong)!!.originalUrl)
    }
}
