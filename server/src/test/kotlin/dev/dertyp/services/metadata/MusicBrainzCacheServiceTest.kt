package dev.dertyp.services.metadata

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MusicBrainzCacheServiceTest {
    private lateinit var database: Database
    private lateinit var service: MusicBrainzCacheService

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "mb_cache_test")
        transaction(database) {
            SchemaUtils.create(*allMusicBrainzTables)
        }

        service = MusicBrainzCacheService()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `staleArtistIdsFlow should return stale artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val staleSince = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()

        transaction(database) {
            MBArtistTable.insert {
                it[id] = id1
                it[name] = "Stale Artist 1"
                it[sortName] = "Stale Artist 1"
                it[lastUpdate] = staleSince - 1000
            }
            MBArtistTable.insert {
                it[id] = id2
                it[name] = "Fresh Artist"
                it[sortName] = "Fresh Artist"
                it[lastUpdate] = staleSince + 1000
            }
            MBArtistTable.insert {
                it[id] = id3
                it[name] = "Stale Artist 2"
                it[sortName] = "Stale Artist 2"
                it[lastUpdate] = staleSince - 2000
            }
        }

        val staleIds = service.staleArtistIdsFlow(staleSince).toList()
        assertEquals(2, staleIds.size)
        assertTrue(staleIds.contains(id1))
        assertTrue(staleIds.contains(id3))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `staleArtistIdsFlow should not skip results when dataset shrinks during iteration`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val staleSince = Clock.System.now().toEpochMilliseconds()

        val ids = (1..250).map { UUID.randomUUID() }
        transaction(database) {
            ids.forEach { uuid ->
                MBArtistTable.insert {
                    it[id] = uuid
                    it[name] = "Artist $uuid"
                    it[sortName] = "Artist $uuid"
                    it[lastUpdate] = staleSince - 1000
                }
            }
        }

        val fetchedIds = mutableListOf<UUID>()
        
        service.staleArtistIdsFlow(staleSince).collect { id ->
            fetchedIds.add(id)
            transaction(database) {
                MBArtistTable.deleteWhere { MBArtistTable.id eq id }
            }
        }

        assertEquals(250, fetchedIds.size)
        assertTrue(fetchedIds.containsAll(ids))

        val secondFetch = service.staleArtistIdsFlow(staleSince).toList()
        assertEquals(0, secondFetch.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `staleReleaseGroupIdsFlow should return stale release groups`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val staleSince = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds
        val id1 = UUID.randomUUID()

        transaction(database) {
            MBReleaseGroupTable.insert {
                it[id] = id1
                it[title] = "Stale Group"
                it[lastUpdate] = staleSince - 1000
            }
        }

        val staleIds = service.staleReleaseGroupIdsFlow(staleSince).toList()
        assertEquals(1, staleIds.size)
        assertEquals(id1, staleIds.first())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `staleReleaseIdsFlow should return stale releases`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val staleSince = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds
        val id1 = UUID.randomUUID()

        transaction(database) {
            MBReleaseTable.insert {
                it[id] = id1
                it[title] = "Stale Release"
                it[lastUpdate] = staleSince - 1000
            }
        }

        val staleIds = service.staleReleaseIdsFlow(staleSince).toList()
        assertEquals(1, staleIds.size)
        assertEquals(id1, staleIds.first())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `staleRecordingIdsFlow should return stale recordings`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val staleSince = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds
        val id1 = UUID.randomUUID()

        transaction(database) {
            MBRecordingTable.insert {
                it[id] = id1
                it[title] = "Stale Recording"
                it[lastUpdate] = staleSince - 1000
            }
        }

        val staleIds = service.staleRecordingIdsFlow(staleSince).toList()
        assertEquals(1, staleIds.size)
        assertEquals(id1, staleIds.first())
    }
}
