package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.AlbumService
import dev.dertyp.services.EntityChangeRecorder
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.relaxedTaskLogService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class BuildAlbumVersionGroupsTest : KoinTest {
    private lateinit var database: Database
    private lateinit var albumService: AlbumService

    private fun setup(dialect: DbDialect) {
        val logService = relaxedTaskLogService()
        albumService = AlbumService()
        startKoin {
            modules(module {
                single { EntityChangeRecorder() }
                single { logService }
                single { albumService }
            })
        }

        database = TestDatabase.connect(dialect, "build_album_version_groups_test")
        transaction(database) {
            SchemaUtils.create(
                *entityChangeTables,
                ImageTable,
                AnimatedImageTable,
                ArtistTable,
                AlbumVersionGroupTable,
                AlbumTable,
                AlbumArtistTable,
                MBReleaseGroupTable,
                MBReleaseTable,
                AlbumMusicBrainzTable,
                ScheduledTaskLogTable
            )
        }
    }

    @AfterEach
    fun tearDown() {
        if (::albumService.isInitialized) runBlocking { albumService.stopService() }
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertAlbum(albumName: String, creditedTo: UUID, releaseGroup: UUID? = null): UUID =
        transaction(database) {
            val newAlbumId = AlbumTable.insertAndGetId { it[name] = albumName }
            AlbumArtistTable.insert {
                it[albumId] = newAlbumId
                it[artistId] = creditedTo
            }
            if (releaseGroup != null) {
                val releaseId = MBReleaseTable.insertAndGetId {
                    it[title] = albumName
                    it[releaseGroupId] = releaseGroup
                }
                AlbumMusicBrainzTable.insert {
                    it[albumId] = newAlbumId
                    it[musicBrainzId] = releaseId
                }
            }
            newAlbumId.value
        }

    private fun versionGroups(): Map<UUID, UUID?> = transaction(database) {
        AlbumTable.selectAll().associate { it[AlbumTable.id].value to it[AlbumTable.versionGroupId]?.value }
    }

    private fun storedGroups(): Set<UUID> = transaction(database) {
        AlbumVersionGroupTable.selectAll().mapTo(mutableSetOf()) { it[AlbumVersionGroupTable.id].value }
    }

    private fun albumsByGroup(groups: Map<UUID, UUID?>): Set<Set<UUID>> =
        groups.entries.groupBy({ it.value }, { it.key }).values.mapTo(mutableSetOf()) { it.toSet() }

    private class Fixtures(
        val linked: UUID,
        val linkedReissue: UUID,
        val unlinked: UUID,
        val sameNameOtherArtist: UUID,
        val alone: UUID
    )

    private fun insertFixtures(): Fixtures = transaction(database) {
        val artist = ArtistTable.insertAndGetId { it[name] = "Artist" }.value
        val otherArtist = ArtistTable.insertAndGetId { it[name] = "Other Artist" }.value
        val releaseGroup = MBReleaseGroupTable.insertAndGetId { it[title] = "Album" }.value
        Fixtures(
            linked = insertAlbum("Album", artist, releaseGroup),
            linkedReissue = insertAlbum("Album Reissue", artist, releaseGroup),
            unlinked = insertAlbum("Album", artist),
            sameNameOtherArtist = insertAlbum("Album", otherArtist),
            alone = insertAlbum("Alone", artist),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `gives every album a group and the editions of an album the same one`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albums = insertFixtures()
        assertEquals(setOf<UUID?>(null), versionGroups().values.toSet())

        BuildAlbumVersionGroups().migrate()

        val groups = versionGroups()
        assertEquals(5, groups.size)
        assertFalse(groups.values.any { it == null })
        assertEquals(
            setOf(
                setOf(albums.linked, albums.linkedReissue, albums.unlinked),
                setOf(albums.sameNameOtherArtist),
                setOf(albums.alone),
            ),
            albumsByGroup(groups)
        )
        assertEquals(groups.values.toSet(), storedGroups())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `is idempotent across repeated runs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertFixtures()

        BuildAlbumVersionGroups().migrate()
        val first = versionGroups()
        val firstStored = storedGroups()
        assertEquals(0, albumService.rebuildVersionGroups())

        BuildAlbumVersionGroups().migrate()

        assertEquals(first, versionGroups())
        assertEquals(firstStored, storedGroups())
        assertEquals(5, first.values.count { it != null })
        assertEquals(3, first.values.toSet().size)
    }
}
