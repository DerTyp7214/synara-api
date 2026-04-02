package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.GenreTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest

class GenreServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: GenreService

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "genre_test")
        transaction(database) {
            SchemaUtils.create(GenreTable)
        }
        service = GenreService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrCreateGenres should create new genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val names = listOf("Rock", "Pop")
        val ids = service.getOrCreateGenres(names)

        assertEquals(2, ids.size)
        
        transaction(database) {
            val count = GenreTable.selectAll().count()
            assertEquals(2L, count)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrCreateGenres should normalize names to lowercase`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val names = listOf("Rock", "rock", "ROCK")
        val ids = service.getOrCreateGenres(names)

        assertEquals(1, ids.size)
        
        transaction(database) {
            val genre = GenreTable.selectAll().single()
            assertEquals("rock", genre[GenreTable.name])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrCreateGenres should return existing genres if they exist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val names1 = listOf("Rock")
        val ids1 = service.getOrCreateGenres(names1)

        val names2 = listOf("rock", "Jazz")
        val ids2 = service.getOrCreateGenres(names2)

        assertEquals(2, ids2.size)
        assertTrue(ids2.contains(ids1[0]))
        
        transaction(database) {
            val count = GenreTable.selectAll().count()
            assertEquals(2L, count)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrCreateGenres should handle empty lists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ids = service.getOrCreateGenres(emptyList())
        assertEquals(0, ids.size)
    }
}
