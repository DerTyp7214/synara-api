package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
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

class SongCreditedNameTest : KoinTest {
    private lateinit var database: Database

    private val allTables = arrayOf(
        ArtistTable, AlbumTable, SongTable, SongVariantTable, SongArtistTable,
        SongMusicBrainzTable, SongAudioDataTable, ImageTable, GenreTable,
        UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
        ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
        PlaylistTable, UserSongTable, UserPlaylistTable,
        SongGenreTable, ArtistGenreTable, AlbumGenreTable,
        PlaylistSongTable, UserPlaylistSongTable,
        SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
        ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
        SongProviderTable,
        CollectionTable, CollectionSongTable, CollectionAlbumTable, CollectionArtistTable, CollectionPlaylistTable,
        *allMusicBrainzTables
    )

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "song_credited_name")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<ArtistService>(relaxed = true) }
                single { mockk<AlbumService>(relaxed = true) }
                single { mockk<GenreService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<PluginManager>(relaxed = true) }
                single { LibraryMergeService() }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId surfaces the credited name while keeping the canonical name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()

        val duoId = UUID.randomUUID()
        val soloId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = duoId; it[name] = "Yung Kafa & Kücük Efendi" }
            ArtistTable.insert { it[id] = soloId; it[name] = "Solo Artist" }
            AlbumTable.insert { it[id] = albumId; it[name] = "Some Album" }
            SongTable.insert { it[id] = songId; it[title] = "Avantgarde"; it[this.albumId] = albumId }

            val aliasId = ArtistAliasTable.insertAndGetId {
                it[artistId] = duoId
                it[name] = "Yung Kafa"
            }

            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = duoId; it[creditedAliasId] = aliasId }
            SongArtistTable.insert { it[this.songId] = songId; it[artistId] = soloId }
        }

        val song = songService.byId(songId)
        assertEquals(2, song?.artists?.size)

        val duo = song?.artists?.single { it.id == duoId }
        assertEquals("Yung Kafa & Kücük Efendi", duo?.name)
        assertEquals("Yung Kafa", duo?.creditedName)

        val solo = song?.artists?.single { it.id == soloId }
        assertEquals("Solo Artist", solo?.name)
        assertNull(solo?.creditedName, "artist without a credited alias must have null creditedName")
    }
}
