package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagInput
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import dev.dertyp.db.TimecodeTagTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class TimecodeTagServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: TimecodeTagService

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        database = TestDatabase.connect(dialect, "timecode_tag_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AlbumTable,
                ArtistTable,
                SongTable, SongVariantTable,
                TimecodeTagTable,
            )
        }
        service = TimecodeTagService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        UserTable.insert {
            it[UserTable.id] = id
            it[username] = "user_$id"
            it[passwordHash] = "hash"
        }
        return id
    }

    private fun insertAlbum(): UUID {
        val id = UUID.randomUUID()
        AlbumTable.insert {
            it[AlbumTable.id] = id
            it[name] = "Album"
        }
        return id
    }

    private fun insertSong(albumId: UUID): UUID {
        val id = UUID.randomUUID()
        SongTable.insert {
            it[SongTable.id] = id
            it[title] = "Song"
            it[SongTable.albumId] = albumId
        }
        return id
    }

    private fun input(type: TimecodeTagType, timestampMs: Long, text: String = "", endMs: Long? = null) =
        TimecodeTagInput(type = type, text = text, timestampMs = timestampMs, endMs = endMs)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createTag then getTags returns the tag with all fields preserved`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }

        val created = service.createTag(userId, songId, TimecodeTagType.CHAPTER, "Intro", 1000L, 2000L)

        assertTrue(created.createdAt > 0)
        assertTrue(created.updatedAt > 0)
        assertEquals(userId, created.userId)
        assertEquals(songId, created.songId)
        assertEquals(TimecodeTagType.CHAPTER, created.type)
        assertEquals("Intro", created.text)
        assertEquals(1000L, created.timestampMs)
        assertEquals(2000L, created.endMs)

        assertEquals(listOf(created), service.getTags(userId, songId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getTags is ordered by timestampMs regardless of insertion order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }

        service.createTag(userId, songId, TimecodeTagType.MARKER, "", 5000L, null)
        service.createTag(userId, songId, TimecodeTagType.MARKER, "", 1000L, null)
        service.createTag(userId, songId, TimecodeTagType.MARKER, "", 3000L, null)

        val tags = service.getTags(userId, songId)
        assertEquals(listOf(1000L, 3000L, 5000L), tags.map { it.timestampMs })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createTag for an unknown song throws`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.createTag(userId, UUID.randomUUID(), TimecodeTagType.NOTE, "", 0L, null) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `validation rejects negative timestamps endMs before timestamp and oversized text`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.createTag(userId, songId, TimecodeTagType.NOTE, "", -1L, null) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.createTag(userId, songId, TimecodeTagType.NOTE, "", 1000L, 500L) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.createTag(userId, songId, TimecodeTagType.NOTE, "x".repeat(1001), 0L, null) }
        }

        assertTrue(service.getTags(userId, songId).isEmpty())

        val accepted = service.createTag(userId, songId, TimecodeTagType.NOTE, "x".repeat(1000), 0L, null)
        assertEquals(1000, accepted.text.length)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateTag changes fields and preserves id and createdAt`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }
        val created = service.createTag(userId, songId, TimecodeTagType.NOTE, "old", 100L, null)

        val updated = service.updateTag(userId, created.id, TimecodeTagType.CHAPTER, "new", 200L, 300L)

        assertEquals(created.id, updated.id)
        assertEquals(created.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt >= created.createdAt)
        assertEquals(TimecodeTagType.CHAPTER, updated.type)
        assertEquals("new", updated.text)
        assertEquals(200L, updated.timestampMs)
        assertEquals(300L, updated.endMs)

        assertEquals(listOf(updated), service.getTags(userId, songId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateTag on a foreign or missing tag throws and leaves it unchanged`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val ownerId = transaction(database) { insertUser() }
        val otherId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }
        val created = service.createTag(ownerId, songId, TimecodeTagType.NOTE, "mine", 100L, null)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.updateTag(otherId, created.id, TimecodeTagType.CHAPTER, "hacked", 999L, null) }
        }
        assertEquals(created, service.getTags(ownerId, songId).single())

        assertThrows<IllegalArgumentException> {
            runBlocking { service.updateTag(ownerId, UUID.randomUUID(), TimecodeTagType.CHAPTER, "x", 0L, null) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteTag returns true once then false, and false for a foreign tag which survives`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ownerId = transaction(database) { insertUser() }
        val otherId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }
        val created = service.createTag(ownerId, songId, TimecodeTagType.NOTE, "", 100L, null)

        assertTrue(service.deleteTag(ownerId, created.id))
        assertFalse(service.deleteTag(ownerId, created.id))

        val survivor = service.createTag(ownerId, songId, TimecodeTagType.NOTE, "", 200L, null)
        assertFalse(service.deleteTag(otherId, survivor.id))
        assertEquals(listOf(survivor), service.getTags(ownerId, songId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `each user's getTags returns only their own tags on a shared song`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }

        val tagA = service.createTag(userA, songId, TimecodeTagType.NOTE, "a", 100L, null)
        val tagB = service.createTag(userB, songId, TimecodeTagType.NOTE, "b", 200L, null)

        assertEquals(listOf(tagA), service.getTags(userA, songId))
        assertEquals(listOf(tagB), service.getTags(userB, songId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `replaceTags replaces only the caller's tags on that song`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId1 = transaction(database) { insertSong(albumId) }
        val songId2 = transaction(database) { insertSong(albumId) }

        service.createTag(userA, songId1, TimecodeTagType.NOTE, "old", 100L, null)
        val otherUsersTag = service.createTag(userB, songId1, TimecodeTagType.NOTE, "other-user", 150L, null)
        val otherSongTag = service.createTag(userA, songId2, TimecodeTagType.NOTE, "other-song", 50L, null)

        val replaced = service.replaceTags(
            userA, songId1,
            listOf(input(TimecodeTagType.MARKER, 300L, "new-2"), input(TimecodeTagType.CHAPTER, 200L, "new-1"))
        )

        assertEquals(listOf(200L, 300L), replaced.map { it.timestampMs })
        assertEquals(replaced, service.getTags(userA, songId1))
        assertEquals(listOf(otherUsersTag), service.getTags(userB, songId1))
        assertEquals(listOf(otherSongTag), service.getTags(userA, songId2))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `replaceTags is atomic and can clear all tags with an empty list`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }
        val existing = service.createTag(userId, songId, TimecodeTagType.NOTE, "keep", 100L, null)

        assertThrows<IllegalArgumentException> {
            runBlocking {
                service.replaceTags(
                    userId, songId,
                    listOf(input(TimecodeTagType.NOTE, 100L), input(TimecodeTagType.NOTE, 500L, endMs = 200L))
                )
            }
        }
        assertEquals(listOf(existing), service.getTags(userId, songId))

        val cleared = service.replaceTags(userId, songId, emptyList())

        assertTrue(cleared.isEmpty())
        assertTrue(service.getTags(userId, songId).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `replaceTags and createTag enforce the per-song tag limit`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }

        val tooMany = (0 until 501).map { input(TimecodeTagType.NOTE, it.toLong()) }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.replaceTags(userId, songId, tooMany) }
        }
        assertTrue(service.getTags(userId, songId).isEmpty())

        val atLimit = (0 until 500).map { input(TimecodeTagType.NOTE, it.toLong()) }
        val replaced = service.replaceTags(userId, songId, atLimit)
        assertEquals(500, replaced.size)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.createTag(userId, songId, TimecodeTagType.NOTE, "", 999L, null) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listTags filters by type paginates and clamps pageSize`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId1 = transaction(database) { insertSong(albumId) }
        val songId2 = transaction(database) { insertSong(albumId) }

        val created = mutableListOf<TimecodeTag>()
        repeat(5) { index ->
            val songId = if (index % 2 == 0) songId1 else songId2
            val type = if (index < 3) TimecodeTagType.CHAPTER else TimecodeTagType.MARKER
            created += service.createTag(userId, songId, type, "tag$index", index * 100L, null)
            delay(2)
        }

        val chapters = service.listTags(userId, TimecodeTagType.CHAPTER, 0, 500)
        assertEquals(3, chapters.total)
        assertTrue(chapters.data.all { it.type == TimecodeTagType.CHAPTER })

        val all = service.listTags(userId, null, 0, 500)
        assertEquals(5, all.total)

        val clampedLow = service.listTags(userId, null, 0, 0)
        assertEquals(1, clampedLow.pageSize)
        assertEquals(1, clampedLow.data.size)

        val clampedHigh = service.listTags(userId, null, 0, 10_000)
        assertEquals(500, clampedHigh.pageSize)

        val page0 = service.listTags(userId, null, 0, 2)
        assertTrue(page0.hasNextPage)
        assertEquals(created.last().id, page0.data.first().id)

        val page1 = service.listTags(userId, null, 1, 2)
        assertTrue(page1.hasNextPage)

        val page2 = service.listTags(userId, null, 2, 2)
        assertFalse(page2.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting the song cascades to its timecode tags`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assumeTrue(dialect == DbDialect.POSTGRES, "FK cascade requires foreign key enforcement")

        val userId = transaction(database) { insertUser() }
        val albumId = transaction(database) { insertAlbum() }
        val songId = transaction(database) { insertSong(albumId) }
        service.createTag(userId, songId, TimecodeTagType.NOTE, "", 100L, null)

        transaction(database) { SongTable.deleteWhere { SongTable.id eq songId } }

        assertTrue(service.getTags(userId, songId).isEmpty())
    }
}
