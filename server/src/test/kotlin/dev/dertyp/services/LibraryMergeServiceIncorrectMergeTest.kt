package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
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
        albumService = mockk {
            coEvery { syncAlbumSongsWithMusicBrainz(any(), any()) } returns Unit
            coEvery { rebuildVersionGroups() } returns 0
        }
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
                ArtistTable, AlbumTable, AlbumTitleTagTable, SongTable, SongVariantTable, ImageTable, PlaylistTable,
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

            val album1 = albums.find {
                it[AlbumTable.songCount] == 1 && it[AlbumTable.cover]?.value == ImageTable.selectAll()
                    .where { ImageTable.path eq "cover1" }.single()[ImageTable.id].value
            }
            val album2 = albums.find {
                it[AlbumTable.songCount] == 1 && it[AlbumTable.cover]?.value == ImageTable.selectAll()
                    .where { ImageTable.path eq "cover2" }.single()[ImageTable.id].value
            }

            assertNotEquals(null, album1)
            assertNotEquals(null, album2)
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
            val rg1 = UUID.randomUUID()
            val rg2 = UUID.randomUUID()

            MBReleaseGroupTable.insert { it[id] = EntityID(rg1, MBReleaseGroupTable); it[title] = "RG1" }
            MBReleaseGroupTable.insert { it[id] = EntityID(rg2, MBReleaseGroupTable); it[title] = "RG2" }

            MBReleaseTable.insert {
                it[id] = EntityID(rel1, MBReleaseTable); it[title] = "R1"; it[releaseGroupId] =
                EntityID(rg1, MBReleaseGroupTable)
            }
            MBReleaseTable.insert {
                it[id] = EntityID(rel2, MBReleaseTable); it[title] = "R2"; it[releaseGroupId] =
                EntityID(rg2, MBReleaseGroupTable)
            }
            MBRecordingTable.insert { it[id] = EntityID(rec1, MBRecordingTable); it[title] = "S1" }
            MBRecordingTable.insert { it[id] = EntityID(rec2, MBRecordingTable); it[title] = "S2" }

            MBRecordingReleaseTable.insert {
                it[recordingId] = EntityID(rec1, MBRecordingTable); it[releaseId] = EntityID(rel1, MBReleaseTable)
            }
            MBRecordingReleaseTable.insert {
                it[recordingId] = EntityID(rec2, MBRecordingTable); it[releaseId] = EntityID(rel2, MBReleaseTable)
            }

            val albumId = AlbumTable.insert {
                it[name] = "Album"
                it[songCount] = 2
            }[AlbumTable.id]

            AlbumMusicBrainzTable.insert {
                it[this.albumId] = albumId
                it[musicBrainzId] = EntityID(rel1, MBReleaseTable)
            }

            val s1 = SongTable.insert {
                it[title] = "Song 1"; it[this.albumId] = albumId; it[filePath] = "p1"; it[trackNumber] = 1
            }[SongTable.id]
            val s2 = SongTable.insert {
                it[title] = "Song 2"; it[this.albumId] = albumId; it[filePath] = "p2"; it[trackNumber] = 2
            }[SongTable.id]

            SongMusicBrainzTable.insert { it[songId] = s1; it[musicBrainzId] = EntityID(rec1, MBRecordingTable) }
            SongMusicBrainzTable.insert { it[songId] = s2; it[musicBrainzId] = EntityID(rec2, MBRecordingTable) }
        }

        val fixed = service.fixIncorrectMerges()
        assertEquals(1, fixed)

        transaction(database) {
            val albums = AlbumTable.selectAll().toList()
            assertEquals(2, albums.size)
            val newAlbum = albums.find { it[AlbumTable.name] == "R2" }
            assertNotEquals(null, newAlbum)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges names the split album after the release and its edition`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            transaction(database) {
                val rel1 = UUID.randomUUID()
                val rel2 = UUID.randomUUID()
                val rec1 = UUID.randomUUID()
                val rec2 = UUID.randomUUID()
                val rg = UUID.randomUUID()

                MBReleaseGroupTable.insert { it[id] = EntityID(rg, MBReleaseGroupTable); it[title] = "RG" }
                MBReleaseTable.insert {
                    it[id] = EntityID(rel1, MBReleaseTable)
                    it[title] = "The Album"
                    it[releaseGroupId] = EntityID(rg, MBReleaseGroupTable)
                }
                MBReleaseTable.insert {
                    it[id] = EntityID(rel2, MBReleaseTable)
                    it[title] = "The Album"
                    it[disambiguation] = "10th Anniversary"
                    it[releaseGroupId] = EntityID(rg, MBReleaseGroupTable)
                }
                MBRecordingTable.insert { it[id] = EntityID(rec1, MBRecordingTable); it[title] = "S1" }
                MBRecordingTable.insert { it[id] = EntityID(rec2, MBRecordingTable); it[title] = "S2" }
                MBRecordingReleaseTable.insert {
                    it[recordingId] = EntityID(rec1, MBRecordingTable); it[releaseId] = EntityID(rel1, MBReleaseTable)
                }
                MBRecordingReleaseTable.insert {
                    it[recordingId] = EntityID(rec2, MBRecordingTable); it[releaseId] = EntityID(rel2, MBReleaseTable)
                }

                val albumId = AlbumTable.insert {
                    it[name] = "The Album"
                    it[songCount] = 2
                }[AlbumTable.id]

                AlbumMusicBrainzTable.insert {
                    it[this.albumId] = albumId
                    it[musicBrainzId] = EntityID(rel1, MBReleaseTable)
                }

                val s1 = SongTable.insert {
                    it[title] = "Song 1"; it[this.albumId] = albumId; it[filePath] = "p1"; it[trackNumber] = 1
                }[SongTable.id]
                val s2 = SongTable.insert {
                    it[title] = "Song 2"; it[this.albumId] = albumId; it[filePath] = "p2"; it[trackNumber] = 2
                }[SongTable.id]

                SongMusicBrainzTable.insert { it[songId] = s1; it[musicBrainzId] = EntityID(rec1, MBRecordingTable) }
                SongMusicBrainzTable.insert { it[songId] = s2; it[musicBrainzId] = EntityID(rec2, MBRecordingTable) }
            }

            assertEquals(1, service.fixIncorrectMerges())

            transaction(database) {
                val albums = AlbumTable.selectAll().toList()
                assertEquals(2, albums.size)
                assertEquals(listOf("The Album", "The Album"), albums.map { it[AlbumTable.name] })

                val split = albums.single { it.albumTitleTags().isNotEmpty() }
                assertEquals(listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary")), split.albumTitleTags())
                assertEquals("The Album (10th Anniversary)", split.fullAlbumName())
                assertEquals(
                    listOf(split[AlbumTable.id] to TitleTagKind.VERSION),
                    AlbumTitleTagTable.selectAll().map { it[AlbumTitleTagTable.albumId] to it[AlbumTitleTagTable.kind] }
                )
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges keeps the name and edition of the original album without a release`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val anniversary = listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary"))

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
                    it[name] = "The Album"
                    it[titleTags] = encodeTitleTags(anniversary)
                    it[cover] = cover1
                    it[songCount] = 2
                }[AlbumTable.id]
                syncAlbumTitleTags(albumId.value, anniversary)

                SongTable.insert {
                    it[title] = "Song 1"; it[this.albumId] = albumId; it[cover] = cover1; it[filePath] = "p1"
                }
                SongTable.insert {
                    it[title] = "Song 2"; it[this.albumId] = albumId; it[cover] = cover2; it[filePath] = "p2"
                }
            }

            assertEquals(1, service.fixIncorrectMerges())

            transaction(database) {
                val albums = AlbumTable.selectAll().toList()
                assertEquals(2, albums.size)
                assertEquals(listOf("The Album", "The Album"), albums.map { it[AlbumTable.name] })
                assertEquals(listOf(anniversary, anniversary), albums.map { it.albumTitleTags() })
                assertEquals(2L, AlbumTitleTagTable.selectAll().count())
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges should split songs with position collisions`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val albumId = AlbumTable.insert {
                it[name] = "Merged Album"
                it[songCount] = 4
            }[AlbumTable.id]

            SongTable.insert {
                it[title] = "S1-A"; it[this.albumId] = albumId; it[trackNumber] = 1; it[filePath] = "p1"
            }
            SongTable.insert {
                it[title] = "S2-A"; it[this.albumId] = albumId; it[trackNumber] = 2; it[filePath] = "p2"
            }
            SongTable.insert {
                it[title] = "S1-B"; it[this.albumId] = albumId; it[trackNumber] = 1; it[filePath] = "p3"
            }
            SongTable.insert {
                it[title] = "S2-B"; it[this.albumId] = albumId; it[trackNumber] = 2; it[filePath] = "p4"
            }
        }

        val fixed = service.fixIncorrectMerges()
        assertEquals(1, fixed)

        transaction(database) {
            val albums = AlbumTable.selectAll().toList()
            assertEquals(2, albums.size)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges should preserve artists and genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val artistId = ArtistTable.insert { it[name] = "Artist" }[ArtistTable.id]
            val genreId = GenreTable.insert { it[name] = "Genre" }[GenreTable.id]
            val cover1 = ImageTable.insert {
                it[id] = UUID.randomUUID(); it[path] = "c1"; it[imageHash] = "h1"; it[origin] = "o"
            }[ImageTable.id]
            val cover2 = ImageTable.insert {
                it[id] = UUID.randomUUID(); it[path] = "c2"; it[imageHash] = "h2"; it[origin] = "o"
            }[ImageTable.id]

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
            for (album in albums) {
                val id = album[AlbumTable.id]
                assertEquals(1, AlbumArtistTable.selectAll().where { AlbumArtistTable.albumId eq id }.count())
                assertEquals(1, AlbumGenreTable.selectAll().where { AlbumGenreTable.albumId eq id }.count())
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges keeps the credited alias, position and join phrase of split album artists`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            transaction(database) {
                SchemaUtils.create(ArtistAliasTable)
                val mainId = ArtistTable.insert { it[name] = "Main" }[ArtistTable.id]
                val guestId = ArtistTable.insert { it[name] = "Guest" }[ArtistTable.id]
                val aliasId =
                    ArtistAliasTable.insert { it[artistId] = mainId; it[name] = "Main Alias" }[ArtistAliasTable.id]
                val cover1 = ImageTable.insert {
                    it[id] = UUID.randomUUID(); it[path] = "c1"; it[imageHash] = "h1"; it[origin] = "o"
                }[ImageTable.id]
                val cover2 = ImageTable.insert {
                    it[id] = UUID.randomUUID(); it[path] = "c2"; it[imageHash] = "h2"; it[origin] = "o"
                }[ImageTable.id]

                val albumId = AlbumTable.insert {
                    it[name] = "Album"
                    it[cover] = cover1
                    it[songCount] = 2
                }[AlbumTable.id]

                AlbumArtistTable.insert {
                    it[this.albumId] = albumId; it[artistId] = mainId; it[creditedAliasId] = aliasId; it[position] =
                    0; it[joinPhrase] = " & "
                }
                AlbumArtistTable.insert {
                    it[this.albumId] = albumId; it[artistId] = guestId; it[position] = 1; it[joinPhrase] = ""
                }

                SongTable.insert {
                    it[title] = "S1"; it[this.albumId] = albumId; it[cover] = cover1; it[filePath] = "p1"
                }
                SongTable.insert {
                    it[title] = "S2"; it[this.albumId] = albumId; it[cover] = cover2; it[filePath] = "p2"
                }
            }

            service.fixIncorrectMerges()

            transaction(database) {
                val albums = AlbumTable.selectAll().toList()
                assertEquals(2, albums.size)
                val aliasId = ArtistAliasTable.selectAll().single()[ArtistAliasTable.id]
                for (album in albums) {
                    val links = AlbumArtistTable.selectAll()
                        .where { AlbumArtistTable.albumId eq album[AlbumTable.id] }
                        .associate {
                            it[AlbumArtistTable.artistId] to Triple(
                                it[AlbumArtistTable.creditedAliasId],
                                it[AlbumArtistTable.position],
                                it[AlbumArtistTable.joinPhrase]
                            )
                        }
                    val main = ArtistTable.selectAll().where { ArtistTable.name eq "Main" }.single()[ArtistTable.id]
                    val guest = ArtistTable.selectAll().where { ArtistTable.name eq "Guest" }.single()[ArtistTable.id]
                    assertEquals(mapOf(main to Triple(aliasId, 0, " & "), guest to Triple(null, 1, "")), links)
                }
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `calculateSimilarity should return 0 for different covers`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val cover1 = ImageTable.insert {
                it[id] = UUID.randomUUID(); it[path] = "c1"; it[imageHash] = "h1"; it[origin] = "o"
            }[ImageTable.id]
            val cover2 = ImageTable.insert {
                it[id] = UUID.randomUUID(); it[path] = "c2"; it[imageHash] = "h2"; it[origin] = "o"
            }[ImageTable.id]

            AlbumTable.insert { it[name] = "Album"; it[cover] = cover1 }
            AlbumTable.insert { it[name] = "Album"; it[cover] = cover2 }

            val albumRow1 = AlbumTable.selectAll().where { AlbumTable.cover eq cover1 }.single()
            val albumRow2 = AlbumTable.selectAll().where { AlbumTable.cover eq cover2 }.single()

            val similarity = service.javaClass.getDeclaredMethod(
                "calculateSimilarity",
                ResultRow::class.java,
                ResultRow::class.java,
                List::class.java,
                List::class.java
            ).apply { isAccessible = true }
                .invoke(service, albumRow1, albumRow2, emptyList<UUID>(), emptyList<UUID>()) as Int

            assertEquals(0, similarity)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges rebuilds the album version groups after a split`(dialect: DbDialect) = runBlocking {
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

        assertEquals(1, service.fixIncorrectMerges())

        coVerify(timeout = 5000) { albumService.rebuildVersionGroups() }
    }
}
