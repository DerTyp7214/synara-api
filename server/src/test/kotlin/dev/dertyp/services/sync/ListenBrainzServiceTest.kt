package dev.dertyp.services.sync

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.services.ListenService
import dev.dertyp.services.SongService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
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
import java.util.*

class ListenBrainzServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ListenBrainzService

    private fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single<HookBus> { mockk(relaxed = true) }
                single { mockk<SongService>() }
                single { ListenService() }
            })
        }
        database = TestDatabase.connect(dialect, "listenbrainz_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AnimatedImageTable,
                AlbumTable,
                SongTable, SongVariantTable,
                MBRecordingTable,
                MBRecordingIsrcTable,
                SongMusicBrainzTable,
                ListenBrainzUserTable,
                UserListenBrainzLinkTable,
                ListenTable,
                ListenLinkTable,
            )
        }
        service = ListenBrainzService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rematchUnmatched links unmatched listens via stored overrides`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbid = UUID.randomUUID()
        val msid = UUID.randomUUID()
        val (lb, song1, song2) = transaction(database) {
            val userId = UUID.randomUUID()
            UserTable.insert {
                it[id] = userId
                it[username] = "user_$userId"
                it[passwordHash] = "x"
            }
            val lbId = UUID.randomUUID()
            ListenBrainzUserTable.insert {
                it[id] = lbId
                it[username] = "lb_$lbId"
            }
            UserListenBrainzLinkTable.insert {
                it[UserListenBrainzLinkTable.userId] = userId
                it[listenBrainzUserId] = lbId
            }
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            fun insertSong(): UUID {
                val sid = UUID.randomUUID()
                SongTable.insert {
                    it[id] = sid
                    it[title] = "Song"
                    it[SongTable.albumId] = albumId
                }
                return sid
            }
            val song1 = insertSong()
            val song2 = insertSong()

            ListenLinkTable.insert {
                it[ListenLinkTable.userId] = userId
                it[songId] = song1
                it[recordingMbid] = mbid
                it[createdAt] = 1
            }
            ListenLinkTable.insert {
                it[ListenLinkTable.userId] = userId
                it[songId] = song2
                it[recordingMsid] = msid
                it[createdAt] = 2
            }

            fun insertListen(at: Long, rMbid: UUID? = null, rMsid: UUID? = null) {
                ListenTable.insert {
                    it[listenBrainzUserId] = lbId
                    it[recordingMbid] = rMbid
                    it[recordingMsid] = rMsid
                    it[listenedAt] = at
                    it[listenSource] = ListenSource.LISTENBRAINZ
                }
            }
            insertListen(100, rMbid = mbid)
            insertListen(200, rMsid = msid)
            insertListen(300, rMbid = UUID.randomUUID())

            Triple(lbId, song1, song2)
        }

        val updated = service.rematchUnmatched(lb)

        assertEquals(2, updated)
        transaction(database) {
            val byMbid = ListenTable.selectAll().where { ListenTable.recordingMbid eq mbid }.single()
            assertEquals(song1, byMbid[ListenTable.songId]?.value)
            val byMsid = ListenTable.selectAll().where { ListenTable.recordingMsid eq msid }.single()
            assertEquals(song2, byMsid[ListenTable.songId]?.value)
            val unrelated = ListenTable.selectAll().where { ListenTable.listenedAt eq 300L }.single()
            assertEquals(null, unrelated[ListenTable.songId])
        }
    }
}
