package dev.dertyp.core

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class BatchedResultsTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "batched_results")
        transaction(database) {
            SchemaUtils.create(AlbumTable, ArtistTable, SongTable, ArtistSplitAliasTable)
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun insertTiedSongs(count: Int): List<UUID> = transaction(database) {
        val albumId = UUID.randomUUID()
        AlbumTable.insert {
            it[id] = albumId
            it[name] = "Album"
        }
        SongTable.batchInsert((1..count).map { UUID.randomUUID() }) { songId ->
            this[SongTable.id] = songId
            this[SongTable.title] = "Song"
            this[SongTable.albumId] = albumId
            this[SongTable.inserted] = 1000L
        }
        SongTable.select(SongTable.id).orderBy(SongTable.id, SortOrder.ASC).map { it[SongTable.id].value }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `offset batches over tied rows with an id tiebreaker neither duplicate nor skip rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val expected = insertTiedSongs(53)

        val result = mutableListOf<UUID>()
        val batchSizes = mutableListOf<Int>()
        SongTable
            .select(SongTable.id)
            .orderBy(SongTable.inserted, SortOrder.DESC)
            .orderBy(SongTable.id, SortOrder.ASC)
            .fetchBatchedResults(5) { batch ->
                batchSizes += batch.size
                result += batch.map { it[SongTable.id].value }
            }

        assertEquals(expected, result)
        assertEquals(11, batchSizes.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `id keyset batches do not skip rows when earlier rows are deleted mid iteration`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val expected = insertTiedSongs(40)

        val result = mutableListOf<UUID>()
        SongTable
            .select(SongTable.id)
            .fetchBatchedResultsByIdKeyset(SongTable.id, 6) { batch ->
                val ids = batch.map { it[SongTable.id].value }
                result += ids
                transaction(database) {
                    ids.take(3).forEach { songId -> SongTable.deleteWhere { SongTable.id eq songId } }
                }
            }

        assertEquals(expected, result)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `composite keyset batches over tied names neither duplicate nor skip rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val names = listOf("Alpha", "Beta", "Gamma")
        transaction(database) {
            val artistIds = (1..15).map { UUID.randomUUID() }
            artistIds.forEach { artistId ->
                ArtistTable.insert {
                    it[id] = artistId
                    it[name] = "Artist"
                }
            }
            ArtistSplitAliasTable.batchInsert(names.flatMap { name -> artistIds.map { name to it } }) { (name, artistId) ->
                this[ArtistSplitAliasTable.name] = name
                this[ArtistSplitAliasTable.artistId] = artistId
            }
        }
        val expected = transaction(database) {
            ArtistSplitAliasTable
                .selectAll()
                .orderBy(ArtistSplitAliasTable.name to SortOrder.ASC, ArtistSplitAliasTable.artistId to SortOrder.ASC)
                .map { it[ArtistSplitAliasTable.name] to it[ArtistSplitAliasTable.artistId].value }
        }

        val result = mutableListOf<Pair<String, UUID>>()
        ArtistSplitAliasTable
            .selectAll()
            .fetchBatchedResultsByKeyset(ArtistSplitAliasTable.name, ArtistSplitAliasTable.artistId, 4) { batch ->
                val rows = batch.map { it[ArtistSplitAliasTable.name] to it[ArtistSplitAliasTable.artistId].value }
                result += rows
                transaction(database) {
                    rows.take(2).forEach { (name, artistId) ->
                        ArtistSplitAliasTable.deleteWhere {
                            (ArtistSplitAliasTable.name eq name) and (ArtistSplitAliasTable.artistId eq artistId)
                        }
                    }
                }
            }

        assertEquals(45, expected.size)
        assertEquals(expected, result)
    }
}
