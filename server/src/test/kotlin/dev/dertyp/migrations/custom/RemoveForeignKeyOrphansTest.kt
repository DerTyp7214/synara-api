package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.PodcastSource
import dev.dertyp.db.*
import dev.dertyp.core.db.dbQuery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoveForeignKeyOrphansTest : KoinTest {
    private fun setup(dialect: DbDialect) = runBlocking {
        startKoin { modules(module { }) }
        TestDatabase.connect(dialect, "remove_foreign_key_orphans_test", foreignKeys = false)
        dbQuery {
            SchemaUtils.create(
                ImageTable,
                UserTable,
                PodcastShowTable,
                PodcastEpisodeTable,
                PodcastTranscriptTable,
                AlbumTable,
                SongTable,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun image() = dbQuery {
        ImageTable.insertAndGetId {
            it[path] = "cover.jpg"
            it[imageHash] = UUID.randomUUID().toString()
            it[origin] = "test"
        }.value
    }

    private suspend fun user(name: String, imageId: UUID?) = dbQuery {
        UserTable.insertAndGetId {
            it[username] = name
            it[passwordHash] = "hash"
            it[profileImage] = imageId
        }.value
    }

    private suspend fun show(key: String) = dbQuery {
        PodcastShowTable.insertAndGetId {
            it[showSource] = PodcastSource.FEED
            it[sourceKey] = key
            it[title] = key
        }.value
    }

    private suspend fun episode(showId: UUID, key: String) = dbQuery {
        PodcastEpisodeTable.insertAndGetId {
            it[PodcastEpisodeTable.showId] = showId
            it[guid] = key
            it[guidKey] = key
            it[title] = key
            it[publishedAt] = 0L
        }.value
    }

    private suspend fun transcript(episodeId: UUID) = dbQuery {
        PodcastTranscriptTable.insertAndGetId {
            it[PodcastTranscriptTable.episodeId] = episodeId
            it[sourceKey] = "transcript"
            it[type] = "text/plain"
        }.value
    }

    private suspend fun song(albumId: UUID) = dbQuery {
        SongTable.insertAndGetId {
            it[SongTable.albumId] = albumId
            it[title] = "song"
        }.value
    }

    private suspend fun album() = dbQuery {
        AlbumTable.insertAndGetId { it[name] = "album" }.value
    }

    @Test
    fun `orphans are removed along cascade chains, cleared on set null and kept on restrict`() = runBlocking {
        setup(DbDialect.SQLITE)
        val keptImage = image()
        val keptUser = user("kept", keptImage)
        val clearedUser = user("cleared", UUID.randomUUID())

        val keptShow = show("kept")
        val keptEpisode = episode(keptShow, "kept")
        val keptTranscript = transcript(keptEpisode)
        val orphanEpisode = episode(UUID.randomUUID(), "orphan")
        transcript(orphanEpisode)

        val keptSong = song(album())
        val restrictedSong = song(UUID.randomUUID())

        RemoveForeignKeyOrphans().migrate()

        dbQuery {
            val profileImages =
                UserTable.selectAll().associate { it[UserTable.id].value to it[UserTable.profileImage]?.value }
            assertEquals(keptImage, profileImages[keptUser])
            assertEquals(setOf(keptUser, clearedUser), profileImages.keys)
            assertNull(profileImages[clearedUser])

            assertEquals(listOf(keptEpisode), PodcastEpisodeTable.selectAll().map { it[PodcastEpisodeTable.id].value })
            assertEquals(
                listOf(keptTranscript),
                PodcastTranscriptTable.selectAll().map { it[PodcastTranscriptTable.id].value })

            assertEquals(setOf(keptSong, restrictedSong), SongTable.selectAll().map { it[SongTable.id].value }.toSet())
            assertEquals(1, SongTable.selectAll().where { SongTable.id eq restrictedSong }.count().toInt())
        }
    }

    @Test
    fun `nothing changes without orphans`() = runBlocking {
        setup(DbDialect.SQLITE)
        val imageId = image()
        val userId = user("kept", imageId)
        val episodeId = episode(show("kept"), "kept")
        transcript(episodeId)
        song(album())

        RemoveForeignKeyOrphans().migrate()

        dbQuery {
            assertEquals(imageId, UserTable.selectAll().single().let {
                assertEquals(userId, it[UserTable.id].value)
                it[UserTable.profileImage]?.value
            })
            assertEquals(1L, PodcastEpisodeTable.selectAll().count())
            assertEquals(1L, PodcastTranscriptTable.selectAll().count())
            assertEquals(1L, SongTable.selectAll().count())
        }
    }
}
