package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.MusicBrainzArtist
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.coEvery
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
import org.koin.test.KoinTest
import java.util.UUID

class ArtistServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ArtistService
    private val musicBrainzService = mockk<MusicBrainzService>()

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { musicBrainzService }
                single { MusicBrainzCacheService() }
                single { mockk<ImageService>(relaxed = true) }
                single { CachedMusicBrainzService(get(), get()) }
            })
        }

        database = TestDatabase.connect(dialect, "artist_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ArtistTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                FollowedArtistTable,
                ArtistSplitAliasTable,
                ImageTable,
                SongTable,
                SongArtistTable,
                AlbumTable,
                AlbumArtistTable,
                GenreTable,
                ArtistGenreTable,
                SongGenreTable,
                AlbumGenreTable,
                *allMusicBrainzTables
            )
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
        val mbId = UUID.randomUUID()
        transaction(database) {
            MBArtistTable.insert {
                it[id] = mbId
                it[this.name] = name
                it[sortName] = name
            }
        }
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
    fun `byGroup should not return duplicate members when an artist is followed by multiple users or has multiple genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        val user1Id = UUID.randomUUID()
        val user2Id = UUID.randomUUID()
        val genre1Id = UUID.randomUUID()
        val genre2Id = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = user1Id
                it[username] = "user1"
                it[passwordHash] = "hash"
            }
            UserTable.insert {
                it[id] = user2Id
                it[username] = "user2"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = testGroupId
                it[name] = "The Group"
                it[isGroup] = true
            }
            ArtistTable.insert {
                it[id] = testMemberId
                it[name] = "The Member"
                it[groupId] = testGroupId
            }
            FollowedArtistTable.insert {
                it[userId] = user1Id
                it[artistId] = testMemberId
            }
            FollowedArtistTable.insert {
                it[userId] = user2Id
                it[artistId] = testMemberId
            }
            GenreTable.insert {
                it[id] = genre1Id
                it[name] = "Genre 1"
            }
            GenreTable.insert {
                it[id] = genre2Id
                it[name] = "Genre 2"
            }
            ArtistGenreTable.insert {
                it[artistId] = testMemberId
                it[genreId] = genre1Id
            }
            ArtistGenreTable.insert {
                it[artistId] = testMemberId
                it[genreId] = genre2Id
            }
        }

        val group = service.byId(testGroupId)
        assertNotNull(group)
        assertEquals(1, group?.artists?.size, "Group should have exactly one member")
        assertEquals(testMemberId, group?.artists?.firstOrNull()?.id)

        val result = service.byGroup(0, 10, testGroupId)
        assertEquals(1, result.data.size, "byGroup should return exactly one member")
        assertEquals(testMemberId, result.data[0].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find artists by MusicBrainz metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Library Name"
            }
            MBArtistTable.insert {
                it[id] = mbId
                it[name] = "MusicBrainz Name"
                it[sortName] = "MusicBrainz Name"
                it[disambiguation] = "Special Disambiguation"
            }
            ArtistMusicBrainzTable.insert {
                it[this.artistId] = artistId
                it[musicBrainzId] = mbId
            }
            MBArtistAliasTable.insert {
                it[MBArtistAliasTable.artistId] = mbId
                it[name] = "MB Alias"
                it[sortName] = "MB Alias"
            }
        }

        val mbNameResult = service.rankedSearch(0, 10, "MusicBrainz")
        assertEquals(1, mbNameResult.data.size)
        assertEquals(artistId, mbNameResult.data[0].id)

        val mbDisambiguationResult = service.rankedSearch(0, 10, "Special")
        assertEquals(1, mbDisambiguationResult.data.size)
        assertEquals(artistId, mbDisambiguationResult.data[0].id)

        val mbAliasResult = service.rankedSearch(0, 10, "Alias")
        assertEquals(1, mbAliasResult.data.size)
        assertEquals(artistId, mbAliasResult.data[0].id)
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

        val mergeArtists = MergeArtists(
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

        val mbId = UUID.randomUUID()
        transaction(database) {
            MBArtistTable.insert {
                it[this.id] = mbId
                it[name] = "Artist"
                it[sortName] = "Artist"
            }
        }
        service.setMusicBrainzId(id, mbId)
        
        val updated = service.byId(id)
        assertEquals(mbId, updated?.musicbrainzId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return isFollowed true if artist is followed by user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user1"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Followed Artist"
            }
            FollowedArtistTable.insert {
                it[FollowedArtistTable.artistId] = artistId
                it[FollowedArtistTable.userId] = userId
            }
        }

        val artist = service.byId(artistId, userId)
        assertNotNull(artist)
        assertEquals(true, artist?.isFollowed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return isFollowed false if artist is not followed by user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user1"
                it[passwordHash] = "hash"
            }
            UserTable.insert {
                it[id] = otherUserId
                it[username] = "user2"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Unfollowed Artist"
            }
            FollowedArtistTable.insert {
                it[FollowedArtistTable.artistId] = artistId
                it[FollowedArtistTable.userId] = otherUserId
            }
        }

        val artist = service.byId(artistId, userId)
        assertNotNull(artist)
        assertEquals(false, artist?.isFollowed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `splitArtist should split one artist into multiple`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist A & Artist B"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Shared Song"
                it[albumId] = UUID.randomUUID().also { albumId ->
                    AlbumTable.insert { album ->
                        album[id] = albumId
                        album[name] = "Album"
                    }
                }
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val splitArtist = dev.dertyp.data.SplitArtist(
            artistId = artistId,
            newArtists = mapOf(
                "Artist A" to null,
                "Artist B" to null
            )
        )

        val result = service.splitArtist(splitArtist)
        assertEquals(2, result.size)
        
        val newArtistIds = result.map { it.id }
        transaction(database) {
            val linkedArtists = SongArtistTable.selectAll().where { SongArtistTable.songId eq songId }.map { it[SongArtistTable.artistId].value }
            assertEquals(2, linkedArtists.size)
            assertTrue(linkedArtists.containsAll(newArtistIds))

            assertNull(ArtistTable.selectAll().where { ArtistTable.id eq artistId }.singleOrNull())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreate should return existing and create new`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val existingId = UUID.randomUUID()
        transaction(database) {
            ArtistTable.insert {
                it[id] = existingId
                it[name] = "Existing"
            }
        }

        val names = listOf("Existing", "New Artist")
        val result = service.getOrBulkCreate(names)
        
        assertEquals(2, result.size)
        assertTrue(result["Existing"]!!.contains(existingId))
        assertTrue(result.containsKey("New Artist"))
        
        val newArtistId = result["New Artist"]!!.first()
        assertNotNull(service.byId(newArtistId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteUnreferencedArtists should remove artists with no songs or albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Unreferenced"
            }
            val referencedId = UUID.randomUUID()
            ArtistTable.insert {
                it[id] = referencedId
                it[name] = "Referenced"
            }
            val songId = UUID.randomUUID()
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[albumId] = UUID.randomUUID().also { albumId ->
                    AlbumTable.insert { album -> album[id] = albumId; album[name] = "Album" }
                }
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = referencedId
            }
        }

        val deletedCount = service.deleteUnreferencedArtists()
        assertEquals(1, deletedCount)
        
        val artists = service.allArtists(0, 10).data
        assertEquals(1, artists.size)
        assertEquals("Referenced", artists[0].name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return artist with genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = UUID.randomUUID()
        val genreId = UUID.randomUUID()
        transaction(database) {
            ArtistTable.insert {
                it[ArtistTable.id] = id
                it[name] = "Artist with Genre"
            }
            GenreTable.insert {
                it[GenreTable.id] = genreId
                it[name] = "jazz"
            }
            ArtistGenreTable.insert {
                it[ArtistGenreTable.artistId] = id
                it[ArtistGenreTable.genreId] = genreId
            }
        }

        val artist = service.byId(id)
        assertNotNull(artist)
        assertEquals(1, artist?.genres?.size)
        assertEquals("jazz", artist?.genres?.firstOrNull()?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeArtists should combine genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId1 = UUID.randomUUID()
        val artistId2 = UUID.randomUUID()
        val genreId1 = UUID.randomUUID()
        val genreId2 = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId1
                it[name] = "Artist 1"
            }
            ArtistTable.insert {
                it[id] = artistId2
                it[name] = "Artist 2"
            }
            GenreTable.insert {
                it[id] = genreId1
                it[name] = "rock"
            }
            GenreTable.insert {
                it[id] = genreId2
                it[name] = "pop"
            }
            ArtistGenreTable.insert {
                it[artistId] = artistId1
                it[genreId] = genreId1
            }
            ArtistGenreTable.insert {
                it[artistId] = artistId2
                it[genreId] = genreId2
            }
        }

        val mergeArtists = MergeArtists(
            name = "Merged Artist",
            artistIds = listOf(artistId1, artistId2),
            image = null
        )

        val merged = service.mergeArtists(mergeArtists)
        assertNotNull(merged)
        assertEquals(2, merged?.genres?.size)
        val genreNames = merged?.genres?.map { it.name }
        assertTrue(genreNames?.contains("rock") == true)
        assertTrue(genreNames?.contains("pop") == true)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setMusicBrainzId should fetch metadata if not in cache`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
        }

        coEvery { musicBrainzService.fetchArtistById(mbId, any()) } returns MusicBrainzArtist(
            id = mbId,
            name = "Fetched Artist",
            sortName = "Fetched Artist"
        )

        service.setMusicBrainzId(artistId, mbId)

        val mbArtist = transaction(database) {
            MBArtistTable.selectAll().where { MBArtistTable.id eq mbId }.singleOrNull()
        }
        assertNotNull(mbArtist)
        assertEquals("Fetched Artist", mbArtist!![MBArtistTable.name])
    }
}
