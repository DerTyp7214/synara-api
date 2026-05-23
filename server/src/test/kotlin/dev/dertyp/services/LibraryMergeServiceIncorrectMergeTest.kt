package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class LibraryMergeServiceIncorrectMergeTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: LibraryMergeService
    private lateinit var environment: ApplicationEnvironment
    private lateinit var songService: SongService
    private lateinit var albumService: AlbumService
    private lateinit var pluginManager: PluginManager

    fun setup(dialect: DbDialect) {
        environment = mockk()
        songService = mockk()
        albumService = mockk()
        pluginManager = mockk()

        startKoin {
            modules(module {
                single { environment }
                single { songService }
                single { albumService }
                single { pluginManager }
            })
        }

        database = TestDatabase.connect(dialect, "merge_fix_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable, AlbumTable, SongTable, ImageTable, PlaylistTable,
                UserTable, UserPlaylistTable, UserPlaylistSongTable, PlaylistSongTable,
                SongArtistTable, AlbumArtistTable, AlbumMusicBrainzTable, SongMusicBrainzTable,
                TranscodedSongTable, UserSongTable, SongProviderTable, AlbumProviderTable,
                AlbumGenreTable, GenreTable,
                *allMusicBrainzTables
            )
        }
        service = LibraryMergeService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges should split songs with different covers`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val cover1 = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "cover1"; it[imageHash] = "hash1"; it[origin] = "o"
            }[ImageTable.id]
            val cover2 = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "cover2"; it[imageHash] = "hash2"; it[origin] = "o"
            }[ImageTable.id]

            val albumId = AlbumTable.insert {
                it[name] = "Album"
                it[cover] = cover1
                it[songCount] = 2
            }[AlbumTable.id]

            SongTable.insert {
                it[title] = "Song 1"
                it[this.albumId] = albumId
                it[cover] = cover1
                it[filePath] = "p1"
            }
            SongTable.insert {
                it[title] = "Song 2"
                it[this.albumId] = albumId
                it[cover] = cover2
                it[filePath] = "p2"
            }
        }

        val fixed = service.fixIncorrectMerges()
        assertEquals(1, fixed)

        transaction(database) {
            val albums = AlbumTable.selectAll().toList()
            assertEquals(2, albums.size)
            
            val album1 = albums.find { it[AlbumTable.songCount] == 1 && it[AlbumTable.cover]?.value == ImageTable.selectAll().where { ImageTable.path eq "cover1" }.single()[ImageTable.id].value }
            val album2 = albums.find { it[AlbumTable.songCount] == 1 && it[AlbumTable.cover]?.value == ImageTable.selectAll().where { ImageTable.path eq "cover2" }.single()[ImageTable.id].value }
            
            assertNotEquals(null, album1)
            assertNotEquals(null, album2)
            assertNotEquals(album1!![AlbumTable.id], album2!![AlbumTable.id])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges should split songs from different MB releases`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val rel1 = UUID.randomUUID()
            val rel2 = UUID.randomUUID()
            val rec1 = UUID.randomUUID()
            val rec2 = UUID.randomUUID()

            MBReleaseTable.insert { it[id] = EntityID(rel1, MBReleaseTable); it[title] = "R1" }
            MBReleaseTable.insert { it[id] = EntityID(rel2, MBReleaseTable); it[title] = "R2" }
            MBRecordingTable.insert { it[id] = EntityID(rec1, MBRecordingTable); it[title] = "S1" }
            MBRecordingTable.insert { it[id] = EntityID(rec2, MBRecordingTable); it[title] = "S2" }

            MBRecordingReleaseTable.insert { it[recordingId] = EntityID(rec1, MBRecordingTable); it[releaseId] = EntityID(rel1, MBReleaseTable) }
            MBRecordingReleaseTable.insert { it[recordingId] = EntityID(rec2, MBRecordingTable); it[releaseId] = EntityID(rel2, MBReleaseTable) }

            val albumId = AlbumTable.insert {
                it[name] = "Album"
                it[songCount] = 2
            }[AlbumTable.id]

            AlbumMusicBrainzTable.insert {
                it[this.albumId] = albumId
                it[musicBrainzId] = EntityID(rel1, MBReleaseTable)
            }

            val s1 = SongTable.insert {
                it[title] = "Song 1"; it[this.albumId] = albumId; it[filePath] = "p1"
            }[SongTable.id]
            val s2 = SongTable.insert {
                it[title] = "Song 2"; it[this.albumId] = albumId; it[filePath] = "p2"
            }[SongTable.id]

            SongMusicBrainzTable.insert { it[songId] = s1; it[musicBrainzId] = EntityID(rec1, MBRecordingTable) }
            SongMusicBrainzTable.insert { it[songId] = s2; it[musicBrainzId] = EntityID(rec2, MBRecordingTable) }
        }

        val fixed = service.fixIncorrectMerges()
        assertEquals(1, fixed)

        transaction(database) {
            val albums = AlbumTable.selectAll().toList()
            assertEquals(2, albums.size)

            val mbLinks = AlbumMusicBrainzTable.selectAll().toList()
            assertEquals(2, mbLinks.size)

            val mbIds = mbLinks.map { it[AlbumMusicBrainzTable.musicBrainzId]?.value }.toSet()
            assertEquals(2, mbIds.size)

            val newAlbum = albums.find { it[AlbumTable.name] == "R2" }
            assertNotEquals(null, newAlbum)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges should move songs to existing album with matching MBID`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val rel1 = UUID.randomUUID()
            val rel2 = UUID.randomUUID()
            val rec1 = UUID.randomUUID()
            val rec2 = UUID.randomUUID()

            MBReleaseTable.insert { it[id] = EntityID(rel1, MBReleaseTable); it[title] = "R1" }
            MBReleaseTable.insert { it[id] = EntityID(rel2, MBReleaseTable); it[title] = "R2" }
            MBRecordingTable.insert { it[id] = EntityID(rec1, MBRecordingTable); it[title] = "S1" }
            MBRecordingTable.insert { it[id] = EntityID(rec2, MBRecordingTable); it[title] = "S2" }

            MBRecordingReleaseTable.insert { it[recordingId] = EntityID(rec1, MBRecordingTable); it[releaseId] = EntityID(rel1, MBReleaseTable) }
            MBRecordingReleaseTable.insert { it[recordingId] = EntityID(rec2, MBRecordingTable); it[releaseId] = EntityID(rel2, MBReleaseTable) }

            val album1Id = AlbumTable.insert { it[name] = "Album 1"; it[songCount] = 2 }[AlbumTable.id]
            val album2Id = AlbumTable.insert { it[name] = "Album 2"; it[songCount] = 0 }[AlbumTable.id]

            AlbumMusicBrainzTable.insert { it[this.albumId] = album1Id; it[this.musicBrainzId] = EntityID(rel1, MBReleaseTable) }
            AlbumMusicBrainzTable.insert { it[this.albumId] = album2Id; it[this.musicBrainzId] = EntityID(rel2, MBReleaseTable) }

            val s1 = SongTable.insert { it[title] = "S1"; it[this.albumId] = album1Id; it[filePath] = "p1" }[SongTable.id]
            val s2 = SongTable.insert { it[title] = "S2"; it[this.albumId] = album1Id; it[filePath] = "p2" }[SongTable.id]

            SongMusicBrainzTable.insert { it[songId] = s1; it[musicBrainzId] = EntityID(rec1, MBRecordingTable) }
            SongMusicBrainzTable.insert { it[songId] = s2; it[musicBrainzId] = EntityID(rec2, MBRecordingTable) }
        }

        val fixed = service.fixIncorrectMerges()
        assertEquals(1, fixed)

        transaction(database) {
            val albums = AlbumTable.selectAll().toList()
            assertEquals(2, albums.size)

            val album1 = albums.find { it[AlbumTable.name] == "Album 1" }!!
            val album2 = albums.find { it[AlbumTable.name] == "Album 2" }!!

            assertEquals(1, album1[AlbumTable.songCount])
            assertEquals(1, album2[AlbumTable.songCount])

            val songs = SongTable.selectAll().toList()
            val s2 = songs.find { it[SongTable.title] == "S2" }!!
            assertEquals(album2[AlbumTable.id], s2[SongTable.albumId])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges should preserve artists and genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val artistId = ArtistTable.insert { it[name] = "Artist" }[ArtistTable.id]
            val genreId = GenreTable.insert { it[name] = "Genre" }[GenreTable.id]
            val cover1 = ImageTable.insert { it[id] = UUID.randomUUID(); it[path] = "c1"; it[imageHash] = "h1"; it[origin] = "o" }[ImageTable.id]
            val cover2 = ImageTable.insert { it[id] = UUID.randomUUID(); it[path] = "c2"; it[imageHash] = "h2"; it[origin] = "o" }[ImageTable.id]

            val albumId = AlbumTable.insert {
                it[name] = "Album"
                it[cover] = cover1
                it[songCount] = 2
            }[AlbumTable.id]

            AlbumArtistTable.insert { it[this.albumId] = albumId; it[this.artistId] = artistId }
            AlbumGenreTable.insert { it[this.albumId] = albumId; it[this.genreId] = genreId }

            SongTable.insert { it[title] = "S1"; it[this.albumId] = albumId; it[cover] = cover1; it[filePath] = "p1" }
            SongTable.insert { it[title] = "S2"; it[this.albumId] = albumId; it[cover] = cover2; it[filePath] = "p2" }
        }

        service.fixIncorrectMerges()

        transaction(database) {
            val albums = AlbumTable.selectAll().toList()
            assertEquals(2, albums.size)
            for (album in albums) {
                val id = album[AlbumTable.id]
                assertEquals(1, AlbumArtistTable.selectAll().where { AlbumArtistTable.albumId eq id }.count())
                assertEquals(1, AlbumGenreTable.selectAll().where { AlbumGenreTable.albumId eq id }.count())
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `calculateSimilarity should return 0 for different covers`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val cover1 = ImageTable.insert { it[id] = UUID.randomUUID(); it[path] = "c1"; it[imageHash] = "h1"; it[origin] = "o" }[ImageTable.id]
            val cover2 = ImageTable.insert { it[id] = UUID.randomUUID(); it[path] = "c2"; it[imageHash] = "h2"; it[origin] = "o" }[ImageTable.id]

            AlbumTable.insert {
                it[name] = "Album"; it[cover] = cover1
            }
            AlbumTable.insert {
                it[name] = "Album"; it[cover] = cover2
            }

            val albumRow1 = AlbumTable.selectAll().where { AlbumTable.cover eq cover1 }.single()
            val albumRow2 = AlbumTable.selectAll().where { AlbumTable.cover eq cover2 }.single()

            val similarity = service.javaClass.getDeclaredMethod("calculateSimilarity", 
                ResultRow::class.java,
                ResultRow::class.java,
                List::class.java, 
                List::class.java
            ).apply { isAccessible = true }.invoke(service, albumRow1, albumRow2, emptyList<UUID>(), emptyList<UUID>()) as Int

            assertEquals(0, similarity)
        }
    }
}
