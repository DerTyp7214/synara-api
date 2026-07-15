package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.RadioSeed
import dev.dertyp.data.RadioType
import dev.dertyp.data.UserSong
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadioServiceTest : KoinTest {

    private val recommendations = mockk<RecommendationServingService>()

    private fun setup(dialect: DbDialect): Set<UUID> = runBlocking {
        TestDatabase.connect(dialect, "radio_test")
        dbQuery { SchemaUtils.create(AlbumTable, SongTable) }

        val albumId = UUID.randomUUID()
        val songIds = (1..50).map { UUID.randomUUID() }
        dbQuery {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            songIds.forEachIndexed { i, sid ->
                SongTable.insert {
                    it[id] = sid
                    it[title] = "Song $i"
                    it[this.albumId] = albumId
                }
            }
        }

        startKoin {
            modules(module {
                single { mockk<SongService>() }
                single { recommendations }
            })
        }
        songIds.toSet()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun RadioService.take(sessionId: UUID, userId: UUID, n: Int): List<UUID> =
        (1..n).map { nextSongId(getSession(sessionId, userId)) }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `random radio returns valid songs without duplicates and resumes`(dialect: DbDialect) = runBlocking {
        val library = setup(dialect)
        val radio = RadioService()
        val userId = UUID.randomUUID()

        val sessionId = radio.createSession(userId, RadioType.RANDOM, null)

        val first = radio.take(sessionId, userId, 30)
        assertTrue(first.all { it in library }, "every emitted id must be a real song")
        assertEquals(30, first.toSet().size, "no duplicates within the library")

        val next = radio.take(sessionId, userId, 10)
        assertEquals(10, next.toSet().size)
        assertTrue(first.intersect(next.toSet()).isEmpty(), "resumed stream must not repeat played songs")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `seed radio plays songs from the recommender`(dialect: DbDialect) = runBlocking {
        val library = setup(dialect)
        val userId = UUID.randomUUID()
        val recommended = library.take(15).toList()
        coEvery { recommendations.similarSongs(any(), any(), any()) } returns
            recommended.map { rid -> mockk<UserSong> { every { id } returns rid } }

        val radio = RadioService()
        val seedId = library.first()
        val sessionId = radio.createSession(userId, RadioType.RANDOM, RadioSeed(songIds = listOf(seedId)))

        val played = radio.take(sessionId, userId, 10)
        assertTrue(played.all { it in recommended }, "seed radio must play recommender output")
        assertEquals(10, played.toSet().size, "no duplicates")
    }
}
