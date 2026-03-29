package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

class ArtistServiceTest {
    private lateinit var database: Database
    private lateinit var service: ArtistService
    private val musicBrainzService = mockk<MusicBrainzService>()

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "artist_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                ArtistSplitAliasTable,
                ImageTable,
                SongTable,
                SongArtistTable,
                AlbumTable,
                AlbumArtistTable
            )
        }
        
        startKoin {
            modules(module {
                single { musicBrainzService }
            })
        }
        
        service = ArtistService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return artist if it exists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = UUID.randomUUID()
        transaction(database) {
            ArtistTable.insert {
                it[ArtistTable.id] = id
                it[name] = "Test Artist"
                it[isGroup] = false
                it[about] = ""
            }
        }

        val artist = service.byId(id)
        assertNotNull(artist)
        assertEquals(id, artist?.id)
        assertEquals("Test Artist", artist?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find artists by name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            val unrelatedGroupId = UUID.randomUUID()
            ArtistTable.insert {
                it[id] = unrelatedGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
                it[about] = ""
            }
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "John Lennon"
                it[isGroup] = false
                it[groupId] = unrelatedGroupId
            }
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Metallica"
                it[isGroup] = true
                it[about] = ""
            }
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Megadeth"
                it[isGroup] = true
                it[about] = ""
            }
        }

        val result = service.rankedSearch(0, 10, "Metal")
        assertEquals(1, result.data.size)
        assertEquals("Metallica", result.data[0].name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find groups by member name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = testGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
            }
            ArtistTable.insert {
                it[id] = testMemberId
                it[name] = "John Lennon"
                it[groupId] = testGroupId
            }
        }

        val result = service.rankedSearch(0, 10, "Lennon")
        assertTrue(result.data.any { it.name == "The Beatles" }, "Should find the group by member name")
        assertTrue(result.data.any { it.name == "John Lennon" }, "Should find the member by its own name")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find members by group name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = testGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
            }
            ArtistTable.insert {
                it[id] = testMemberId
                it[name] = "John Lennon"
                it[groupId] = testGroupId
            }
        }

        val result = service.rankedSearch(0, 10, "Beatles")
        assertTrue(result.data.any { it.name == "John Lennon" }, "Should find the member by group name")
        assertTrue(result.data.any { it.name == "The Beatles" }, "Should find the group by its own name")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should support negative keywords with group member name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        val testArtistId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = testGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
            }
            ArtistTable.insert {
                it[id] = testMemberId
                it[name] = "John Lennon"
                it[groupId] = testGroupId
            }
            ArtistTable.insert {
                it[id] = testArtistId
                it[name] = "Beatles Tribute"
            }
        }

        val result = service.rankedSearch(0, 10, "Beatles -Lennon")
        assertTrue(result.data.any { it.name == "Beatles Tribute" })
        assertFalse(result.data.any { it.name == "John Lennon" })
        assertFalse(result.data.any { it.name == "The Beatles" })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find all members by group name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMember1Id = UUID.randomUUID()
        val testMember2Id = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = testGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
            }
            ArtistTable.insert {
                it[id] = testMember1Id
                it[name] = "John Lennon"
                it[groupId] = testGroupId
            }
            ArtistTable.insert {
                it[id] = testMember2Id
                it[name] = "Paul McCartney"
                it[groupId] = testGroupId
            }
        }

        val result = service.rankedSearch(0, 10, "Beatles")
        assertTrue(result.data.any { it.name == "John Lennon" })
        assertTrue(result.data.any { it.name == "Paul McCartney" })
        assertTrue(result.data.any { it.name == "The Beatles" })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createArtist should create a new artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val name = "New Artist"
        val created = service.createArtist(name)
        
        assertNotNull(created)
        assertEquals(name, created.name)
        
        val fromDb = service.byId(created.id)
        assertNotNull(fromDb)
        assertEquals(name, fromDb?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createArtist should support more data`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val name = "New Group"
        val about = "About the group"
        val mbId = UUID.randomUUID().toString()
        val created = service.createArtist(name, isGroup = true, about = about, musicBrainzId = mbId)
        
        assertNotNull(created)
        assertEquals(name, created.name)
        assertEquals(true, created.isGroup)
        assertEquals(about, created.about)
        assertEquals(mbId, created.musicbrainzId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byIds should return multiple artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ids = List(3) { UUID.randomUUID() }
        transaction(database) {
            ids.forEachIndexed { index, id ->
                ArtistTable.insert {
                    it[ArtistTable.id] = id
                    it[name] = "Artist $index"
                }
            }
        }

        val artists = service.byIds(ids)
        assertEquals(3, artists.size)
        assertEquals(ids.toSet(), artists.map { it.id }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allArtists should return paginated results`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            repeat(5) {
                ArtistTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = "Artist $it"
                }
            }
        }

        val result = service.allArtists(0, 3)
        assertEquals(3, result.data.size)
        assertEquals(5, result.total)
        assertEquals(true, result.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setGroup and byGroup should manage artist groups`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val groupId = UUID.randomUUID()
        val memberIds = List(2) { UUID.randomUUID() }
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = groupId
                it[name] = "The Group"
                it[isGroup] = true
            }
            memberIds.forEach { memberId ->
                ArtistTable.insert {
                    it[id] = memberId
                    it[name] = "Member $memberId"
                }
            }
        }

        service.setGroup(groupId, memberIds)
        
        val group = service.byId(groupId)
        assertEquals(2, group?.artists?.size)
        
        val membersResult = service.byGroup(0, 10, groupId)
        assertEquals(2, membersResult.data.size)
        assertEquals(memberIds.toSet(), membersResult.data.map { it.id }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeArtists should combine multiple artists into one`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId1 = UUID.randomUUID()
        val artistId2 = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId1
                it[name] = "Artist A"
            }
            ArtistTable.insert {
                it[id] = artistId2
                it[name] = "Artist B"
            }
        }

        val mergeArtists = dev.dertyp.data.MergeArtists(
            name = "Merged Artist",
            artistIds = listOf(artistId1, artistId2),
            image = null
        )

        val merged = service.mergeArtists(mergeArtists)
        assertNotNull(merged)
        assertEquals("Merged Artist", merged?.name)

        val aliases = transaction(database) {
            ArtistAliasTable.selectAll().where { ArtistAliasTable.artistId eq merged!!.id }.map { it[ArtistAliasTable.name] }
        }
        assertTrue(aliases.contains("Artist A"))
        assertTrue(aliases.contains("Artist B"))

        assertEquals(null, service.byId(artistId1))
        assertEquals(null, service.byId(artistId2))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setMusicBrainzId should update mbId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = UUID.randomUUID()
        transaction(database) {
            ArtistTable.insert {
                it[ArtistTable.id] = id
                it[name] = "Artist"
            }
        }

        val mbId = "mb-id-123"
        service.setMusicBrainzId(id, mbId)
        
        val updated = service.byId(id)
        assertEquals(mbId, updated?.musicbrainzId)
    }
}
