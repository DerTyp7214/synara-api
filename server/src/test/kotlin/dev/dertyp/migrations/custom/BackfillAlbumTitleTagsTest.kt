package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.db.*
import dev.dertyp.testing.relaxedTaskLogService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class BackfillAlbumTitleTagsTest : KoinTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        val logService = relaxedTaskLogService()
        startKoin { modules(module { single { logService } }) }

        database = TestDatabase.connect(dialect, "backfill_album_title_tags_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                AnimatedImageTable,
                AlbumTable,
                AlbumTitleTagTable,
                MBReleaseGroupTable,
                MBReleaseTable,
                AlbumMusicBrainzTable,
                ScheduledTaskLogTable
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertAlbum(albumName: String, tagsJson: String = "[]", releaseDisambiguation: String? = null): UUID {
        val newAlbumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = newAlbumId
                it[name] = albumName
                it[titleTags] = tagsJson
            }
            if (releaseDisambiguation != null) {
                val releaseId = UUID.randomUUID()
                MBReleaseTable.insert {
                    it[id] = EntityID(releaseId, MBReleaseTable)
                    it[title] = albumName
                    it[disambiguation] = releaseDisambiguation
                }
                AlbumMusicBrainzTable.insert {
                    it[albumId] = newAlbumId
                    it[musicBrainzId] = EntityID(releaseId, MBReleaseTable)
                }
            }
        }
        return newAlbumId
    }

    private fun rowsById(): Map<UUID, ResultRow> = transaction(database) {
        AlbumTable.selectAll().associateBy { it[AlbumTable.id].value }
    }

    private fun kindsById(): Map<UUID, Set<TitleTagKind>> = transaction(database) {
        AlbumTitleTagTable.selectAll()
            .groupBy({ it[AlbumTitleTagTable.albumId].value }, { it[AlbumTitleTagTable.kind] })
            .mapValues { (_, kinds) -> kinds.toSet() }
    }

    private fun insertFixtures(): List<UUID> = listOf(
        insertAlbum("Record (Deluxe Edition)"),
        insertAlbum("The Album", releaseDisambiguation = "10th Anniversary"),
        insertAlbum("The Album", releaseDisambiguation = "special clear vinyl"),
        insertAlbum("Live at Wembley (Bootleg)"),
        insertAlbum("Record (2011 Remaster)", """[{"kind":"VERSION","label":"Deluxe Edition"}]"""),
        insertAlbum("Plain", releaseDisambiguation = ""),
        insertAlbum("Both (Expanded)", releaseDisambiguation = " Deluxe Edition "),
    )

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `takes edition tags from the release disambiguation and the stored name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albums = insertFixtures()
        val suffix = albums[0]
        val disambiguated = albums[1]
        val unclassified = albums[2]
        val untouched = albums[3]
        val preTagged = albums[4]
        val plain = albums[5]
        val both = albums[6]

        BackfillAlbumTitleTags().migrate()

        val rows = rowsById()
        val kinds = kindsById()

        assertEquals("Record", rows[suffix]!![AlbumTable.name])
        assertEquals(listOf(TitleTag(TitleTagKind.VERSION, "Deluxe Edition")), rows[suffix]!!.albumTitleTags())
        assertEquals(setOf(TitleTagKind.VERSION), kinds[suffix])

        assertEquals("The Album", rows[disambiguated]!![AlbumTable.name])
        assertEquals(
            listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary")),
            rows[disambiguated]!!.albumTitleTags(),
        )
        assertEquals(setOf(TitleTagKind.VERSION), kinds[disambiguated])

        assertEquals("The Album", rows[unclassified]!![AlbumTable.name])
        assertEquals("[]", rows[unclassified]!![AlbumTable.titleTags])
        assertEquals(null, kinds[unclassified])

        assertEquals("Live at Wembley (Bootleg)", rows[untouched]!![AlbumTable.name])
        assertEquals("[]", rows[untouched]!![AlbumTable.titleTags])

        assertEquals("Record", rows[preTagged]!![AlbumTable.name])
        assertEquals(
            listOf(
                TitleTag(TitleTagKind.VERSION, "Deluxe Edition"),
                TitleTag(TitleTagKind.REMASTER, "2011 Remaster"),
            ),
            rows[preTagged]!!.albumTitleTags(),
        )
        assertEquals(setOf(TitleTagKind.VERSION, TitleTagKind.REMASTER), kinds[preTagged])

        assertEquals("Plain", rows[plain]!![AlbumTable.name])
        assertEquals("[]", rows[plain]!![AlbumTable.titleTags])

        assertEquals("Both", rows[both]!![AlbumTable.name])
        assertEquals(
            listOf(
                TitleTag(TitleTagKind.VERSION, "Deluxe Edition"),
                TitleTag(TitleTagKind.VERSION, "Expanded"),
            ),
            rows[both]!!.albumTitleTags(),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `is idempotent across repeated runs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertFixtures()

        BackfillAlbumTitleTags().migrate()
        val first = rowsById().mapValues { (_, row) -> row[AlbumTable.name] to row[AlbumTable.titleTags] }
        val firstKinds = kindsById()

        BackfillAlbumTitleTags().migrate()
        val second = rowsById().mapValues { (_, row) -> row[AlbumTable.name] to row[AlbumTable.titleTags] }

        assertEquals(first, second)
        assertEquals(firstKinds, kindsById())
    }
}
