package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.db.dbQuery
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AlbumVersionGroupTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.db.SongTable
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class SearchIndexRebuildWorkerTest : KoinTest {

    private data class Library(val song: UUID, val album: UUID, val artist: UUID)

    private fun setup(dialect: DbDialect): Library = runBlocking {
        TestDatabase.connect(
            dialect, "search_index_rebuild_worker_test",
            ImageTable, ArtistTable, AlbumVersionGroupTable, AlbumTable, SongTable
        )
        startKoin {
            modules(module {
                single { ServerConfig(MapApplicationConfig()) }
            })
        }
        dbQuery {
            val artist = ArtistTable.insertAndGetId { it[name] = "Indexed Artist" }
            val album = AlbumTable.insertAndGetId { it[name] = "Indexed Album" }
            val song = SongTable.insertAndGetId {
                it[title] = "Indexed Song"
                it[albumId] = album
            }
            Library(song.value, album.value, artist.value)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @Test
    fun `queues every song, album and artist on PostgreSQL`() = runBlocking {
        val library = setup(DbDialect.POSTGRES)
        dbQuery { SchemaUtils.create(SearchIndexQueueTable) }

        val result = SearchIndexRebuildWorker().run()

        assertEquals(mapOf<String, Any?>("queuedSongs" to 1, "queuedAlbums" to 1, "queuedArtists" to 1), result)
        assertEquals(
            setOf(
                SearchIndexEntityType.SONG to library.song,
                SearchIndexEntityType.ALBUM to library.album,
                SearchIndexEntityType.ARTIST to library.artist,
            ),
            dbQuery {
                SearchIndexQueueTable.selectAll()
                    .map { it[SearchIndexQueueTable.entityType] to it[SearchIndexQueueTable.entityId] }
                    .toSet()
            }
        )
    }

    @Test
    fun `queues nothing on SQLite, where the queue table does not exist`() = runBlocking {
        setup(DbDialect.SQLITE)

        val result = SearchIndexRebuildWorker().run()

        assertEquals(mapOf<String, Any?>("queuedSongs" to 0, "queuedAlbums" to 0, "queuedArtists" to 0), result)
    }
}
