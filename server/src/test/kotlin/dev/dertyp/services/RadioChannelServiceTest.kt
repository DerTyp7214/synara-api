package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AnimatedImageTable
import dev.dertyp.db.ArtistAliasTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.RadioChannelAlbumTable
import dev.dertyp.db.RadioChannelArtistTable
import dev.dertyp.db.RadioChannelSongTable
import dev.dertyp.db.RadioChannelTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
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

class RadioChannelServiceTest : KoinTest {

    private val creatorId = UUID.randomUUID()
    private val channelAlbum = UUID.randomUUID()
    private val channelArtist = UUID.randomUUID()
    private val otherAlbum = UUID.randomUUID()
    private val songExplicit = UUID.randomUUID()    // member via SONG item
    private val songInAlbum = UUID.randomUUID()     // member via ALBUM item
    private val songByArtistA = UUID.randomUUID()   // member via ARTIST item
    private val songByArtistB = UUID.randomUUID()   // member via ARTIST item
    private val songUnrelated = UUID.randomUUID()   // not a member

    private val members = setOf(songExplicit, songInAlbum, songByArtistA, songByArtistB)

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "radio_channel_test")
        dbQuery {
            SchemaUtils.create(
                ImageTable, AnimatedImageTable, UserTable, AlbumTable, ArtistTable, ArtistAliasTable,
                SongTable, SongArtistTable, AlbumArtistTable,
                RadioChannelTable, RadioChannelSongTable, RadioChannelArtistTable, RadioChannelAlbumTable,
            )
            UserTable.insert { it[id] = creatorId; it[username] = "admin"; it[passwordHash] = "x" }
            AlbumTable.insert { it[id] = channelAlbum; it[name] = "Channel Album" }
            AlbumTable.insert { it[id] = otherAlbum; it[name] = "Other Album" }
            ArtistTable.insert { it[id] = channelArtist; it[name] = "Channel Artist" }

            fun song(songId: UUID, album: UUID) = SongTable.insert { it[id] = songId; it[title] = "s"; it[albumId] = album }
            song(songExplicit, otherAlbum)
            song(songInAlbum, channelAlbum)
            song(songByArtistA, otherAlbum)
            song(songByArtistB, otherAlbum)
            song(songUnrelated, otherAlbum)
            SongArtistTable.insert { it[songId] = songByArtistA; it[artistId] = channelArtist }
            SongArtistTable.insert { it[songId] = songByArtistB; it[artistId] = channelArtist }
        }

        startKoin { modules(module { single { mockk<ImageService>() } }) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun configuredChannel(service: RadioChannelService): UUID {
        val id = service.create(InsertableRadioChannel(name = "Chill"), creatorId)
        assertTrue(service.addItem(id, RadioChannelItemType.SONG, songExplicit))
        assertTrue(service.addItem(id, RadioChannelItemType.ARTIST, channelArtist))
        assertTrue(service.addItem(id, RadioChannelItemType.ALBUM, channelAlbum))
        return id
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `randomSongs draws only configured content`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()
        val id = configuredChannel(service)

        assertEquals(members, service.randomSongs(id, emptySet(), 100).toSet())

        val channel = service.byId(id)!!
        assertEquals(1, channel.songCount)
        assertEquals(1, channel.artistCount)
        assertEquals(1, channel.albumCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `randomSongs honours the exclude set`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()
        val id = configuredChannel(service)

        val drawn = service.randomSongs(id, setOf(songExplicit, songInAlbum), 100).toSet()
        assertEquals(setOf(songByArtistA, songByArtistB), drawn, "excluded members must not be drawn")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `list hides drafts unless includeDisabled`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()

        val published = service.create(InsertableRadioChannel(name = "Published", enabled = true), creatorId)
        val draft = service.create(InsertableRadioChannel(name = "Draft", enabled = false), creatorId)

        val visible = service.list(includeDisabled = false).map { it.id }
        assertTrue(published in visible)
        assertTrue(draft !in visible)

        val all = service.list(includeDisabled = true).map { it.id }
        assertTrue(published in all && draft in all)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `addItem rejects unknown entity`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = RadioChannelService()
        val id = service.create(InsertableRadioChannel(name = "X"), creatorId)
        assertTrue(!service.addItem(id, RadioChannelItemType.SONG, UUID.randomUUID()), "non-existent song is rejected")
    }
}
