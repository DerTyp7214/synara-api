package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.ArtistService
import dev.dertyp.services.ScheduledTaskLogService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class PopulateCreditedArtistNamesTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { ArtistService() }
            })
        }

        database = TestDatabase.connect(dialect, "populate_credited_names_test")
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
    fun `backfills credited names from cached musicbrainz credits`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val duoId = UUID.randomUUID()
        val duoMbId = UUID.randomUUID()
        
        val soloId = UUID.randomUUID()
        val soloMbId = UUID.randomUUID()

        val songId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = duoId; it[name] = "Yung Kafa & Kücük Efendi" }
            ArtistTable.insert { it[id] = soloId; it[name] = "Solo Artist" }

            MBArtistTable.insert { it[id] = duoMbId; it[name] = "Yung Kafa & Kücük Efendi"; it[sortName] = "Yung Kafa & Kücük Efendi" }
            MBArtistTable.insert { it[id] = soloMbId; it[name] = "Solo Artist"; it[sortName] = "Solo Artist" }

            ArtistMusicBrainzTable.insert { it[artistId] = duoId; it[musicBrainzId] = duoMbId }
            ArtistMusicBrainzTable.insert { it[artistId] = soloId; it[musicBrainzId] = soloMbId }

            AlbumTable.insert { it[id] = albumId; it[name] = "Some Album" }
            SongTable.insert { it[id] = songId; it[title] = "Avantgarde"; it[this.albumId] = albumId }

            MBRecordingTable.insert { it[id] = recordingId; it[title] = "Avantgarde" }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = recordingId }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId
                it[artistId] = duoMbId
                it[name] = "Yung Kafa"
                it[position] = 0
            }
            MBRecordingArtistCreditTable.insert {
                it[this.recordingId] = recordingId
                it[artistId] = soloMbId
                it[name] = "Solo Artist"
                it[position] = 1
            }

            MBReleaseGroupTable.insert { it[id] = UUID.randomUUID(); it[title] = "Some Album" }
            MBReleaseTable.insert { it[id] = releaseId; it[title] = "Some Album" }
            AlbumMusicBrainzTable.insert { it[this.albumId] = albumId; it[musicBrainzId] = releaseId }
            MBReleaseArtistCreditTable.insert {
                it[this.releaseId] = releaseId
                it[artistId] = duoMbId
                it[name] = "Yung Kafa"
                it[position] = 0
            }

            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = duoId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = soloId }
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[artistId] = duoId }
        }

        PopulateCreditedArtistNames().migrate()

        transaction(database) {
            val duoSongRow = SongArtistTable.selectAll()
                .where { (SongArtistTable.songId eq songId) and (SongArtistTable.artistId eq duoId) }
                .single()
            val duoAliasId = duoSongRow[SongArtistTable.creditedAliasId]
            assertNotNull(duoAliasId, "duo should have a credited alias on the song")

            val aliasName = ArtistAliasTable.selectAll()
                .where { ArtistAliasTable.id eq duoAliasId!! }
                .single()[ArtistAliasTable.name]
            assertEquals("Yung Kafa", aliasName)

            val aliasOwner = ArtistAliasTable.selectAll()
                .where { ArtistAliasTable.id eq duoAliasId!! }
                .single()[ArtistAliasTable.artistId].value
            assertEquals(duoId, aliasOwner)

            val soloSongRow = SongArtistTable.selectAll()
                .where { (SongArtistTable.songId eq songId) and (SongArtistTable.artistId eq soloId) }
                .single()
            assertNull(soloSongRow[SongArtistTable.creditedAliasId], "matching-name credit must not set an alias")

            val duoAlbumRow = AlbumArtistTable.selectAll()
                .where { (AlbumArtistTable.albumId eq albumId) and (AlbumArtistTable.artistId eq duoId) }
                .single()
            assertNotNull(duoAlbumRow[AlbumArtistTable.creditedAliasId], "duo should have a credited alias on the album")
        }
    }
}
