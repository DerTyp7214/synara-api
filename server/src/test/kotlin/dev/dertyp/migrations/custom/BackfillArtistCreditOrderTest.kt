package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.ArtistService
import dev.dertyp.testing.relaxedTaskLogService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class BackfillArtistCreditOrderTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        val logService = relaxedTaskLogService()

        startKoin {
            modules(module {
                single { logService }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { ArtistService() }
            })
        }

        database = TestDatabase.connect(dialect, "backfill_artist_credit_order_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                ArtistTable,
                ArtistAliasTable,
                AlbumTable,
                SongTable, SongVariantTable,
                SongArtistTable,
                AlbumArtistTable,
                ArtistMusicBrainzTable,
                SongMusicBrainzTable,
                AlbumMusicBrainzTable,
                MBArtistTable,
                MBRecordingTable,
                MBReleaseGroupTable,
                MBReleaseTable,
                MBRecordingArtistCreditTable,
                MBReleaseArtistCreditTable,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `orders song and album artists by their cached musicbrainz credit`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val firstId = UUID.fromString("ffffffff-0000-0000-0000-000000000001")
        val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val unmatchedId = UUID.fromString("88888888-0000-0000-0000-000000000003")
        val firstMbId = UUID.randomUUID()
        val secondMbId = UUID.randomUUID()

        val songId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = firstId; it[name] = "First" }
            ArtistTable.insert { it[id] = secondId; it[name] = "Second" }
            ArtistTable.insert { it[id] = unmatchedId; it[name] = "Unmatched" }

            MBArtistTable.insert { it[id] = firstMbId; it[name] = "First"; it[sortName] = "First" }
            MBArtistTable.insert { it[id] = secondMbId; it[name] = "Second"; it[sortName] = "Second" }

            ArtistMusicBrainzTable.insert { it[artistId] = firstId; it[musicBrainzId] = firstMbId }
            ArtistMusicBrainzTable.insert { it[artistId] = secondId; it[musicBrainzId] = secondMbId }

            AlbumTable.insert { it[id] = albumId; it[name] = "Some Album" }
            SongTable.insert { it[id] = songId; it[title] = "Duet"; it[this.albumId] = albumId }

            MBRecordingTable.insert { it[id] = recordingId; it[title] = "Duet" }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = recordingId }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId
                it[artistId] = firstMbId
                it[name] = "First"
                it[joinPhrase] = " feat. "
                it[position] = 0
            }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId
                it[artistId] = secondMbId
                it[name] = "Second"
                it[joinPhrase] = " & "
                it[position] = 1
            }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId
                it[artistId] = firstMbId
                it[name] = "First"
                it[joinPhrase] = ""
                it[position] = 2
            }

            MBReleaseGroupTable.insert { it[id] = UUID.randomUUID(); it[title] = "Some Album" }
            MBReleaseTable.insert { it[id] = releaseId; it[title] = "Some Album" }
            AlbumMusicBrainzTable.insert { it[this.albumId] = albumId; it[musicBrainzId] = releaseId }
            MBReleaseArtistCreditTable.insert {
                it[this.releaseId] = releaseId
                it[artistId] = secondMbId
                it[name] = "Second"
                it[joinPhrase] = " x "
                it[position] = 0
            }
            MBReleaseArtistCreditTable.insert {
                it[this.releaseId] = releaseId
                it[artistId] = firstMbId
                it[name] = "First"
                it[joinPhrase] = ""
                it[position] = 1
            }

            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = firstId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = secondId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = unmatchedId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = firstId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = secondId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = unmatchedId }
        }

        BackfillArtistCreditOrder().migrate()

        transaction(database) {
            fun songLink(artistId: UUID): ResultRow = SongArtistTable.selectAll()
                .where { (SongArtistTable.songId eq songId) and (SongArtistTable.artistId eq artistId) }
                .single()

            fun albumLink(artistId: UUID): ResultRow = AlbumArtistTable.selectAll()
                .where { (AlbumArtistTable.albumId eq albumId) and (AlbumArtistTable.artistId eq artistId) }
                .single()

            assertEquals(0, songLink(firstId)[SongArtistTable.position])
            assertEquals(" feat. ", songLink(firstId)[SongArtistTable.joinPhrase])
            assertEquals(1, songLink(secondId)[SongArtistTable.position])
            assertEquals(" & ", songLink(secondId)[SongArtistTable.joinPhrase])
            assertEquals(2, songLink(unmatchedId)[SongArtistTable.position])
            assertNull(songLink(unmatchedId)[SongArtistTable.joinPhrase])

            assertEquals(1, albumLink(firstId)[AlbumArtistTable.position])
            assertEquals("", albumLink(firstId)[AlbumArtistTable.joinPhrase])
            assertEquals(0, albumLink(secondId)[AlbumArtistTable.position])
            assertEquals(" x ", albumLink(secondId)[AlbumArtistTable.joinPhrase])
            assertEquals(2, albumLink(unmatchedId)[AlbumArtistTable.position])
            assertNull(albumLink(unmatchedId)[AlbumArtistTable.joinPhrase])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `leaves songs without a cached recording untouched`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Solo" }
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = songId; it[title] = "Song"; it[this.albumId] = albumId }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = null }
            SongArtistTable.insert { it[this.songId] = songId; it[this.artistId] = artistId; it[position] = 3; it[joinPhrase] = " with " }
        }

        BackfillArtistCreditOrder().migrate()

        transaction(database) {
            val row = SongArtistTable.selectAll().where { SongArtistTable.songId eq songId }.single()
            assertEquals(3, row[SongArtistTable.position])
            assertEquals(" with ", row[SongArtistTable.joinPhrase])
        }
    }
}
