package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.EpisodePlaybackReport
import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.data.PodcastScanResult
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastShowSettings
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.ReleaseType
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.db.UserCapabilityTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.models.RecentRelease
import dev.dertyp.services.podcast.PodcastFeedService
import dev.dertyp.services.podcast.PodcastImportService
import dev.dertyp.services.podcast.PodcastIndexService
import dev.dertyp.services.podcast.PodcastLocalScanService
import dev.dertyp.services.podcast.PodcastMaintenanceService
import dev.dertyp.services.podcast.PodcastService
import dev.dertyp.services.podcast.PodcastStreamService
import dev.dertyp.services.podcast.RpcPodcastService
import dev.dertyp.utils.withAuthorization
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.lang.reflect.UndeclaredThrowableException
import java.util.UUID
import kotlin.test.assertFailsWith

class CapabilityAuthorizationTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "cap_auth_test")
        transaction(database) {
            SchemaUtils.create(UserTable, UserCapabilityTable)
        }
        
        startKoin {
            modules(module {
                single { mockk<SongService>(relaxed = true) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should throw UnauthorizedException when capability is missing`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithoutEdit = User(
            id = UUID.randomUUID(),
            username = "noedit",
            passwordHash = "",
            isAdmin = false,
            capabilities = emptyList()
        )

        val songService = mockk<SongService>(relaxed = true)
        val rpcService = SongRpcService(userWithoutEdit, songService)
        val authorizedService = rpcService.withAuthorization<ISongService>(userWithoutEdit)

        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.setLyrics(UUID.randomUUID(), listOf("lyrics"))
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should allow access when capability is present`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithEdit = User(
            id = UUID.randomUUID(),
            username = "withedit",
            passwordHash = "",
            isAdmin = false,
            capabilities = listOf(UserCapability.EDIT)
        )

        val songService = mockk<SongService>(relaxed = true)
        val rpcService = SongRpcService(userWithEdit, songService)
        val authorizedService = rpcService.withAuthorization<ISongService>(userWithEdit)

        authorizedService.setLyrics(UUID.randomUUID(), listOf("lyrics"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should allow access when user is admin regardless of capabilities`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val adminUser = User(
            id = UUID.randomUUID(),
            username = "admin",
            passwordHash = "",
            isAdmin = true,
            capabilities = emptyList()
        )

        val songService = mockk<SongService>(relaxed = true)
        val rpcService = SongRpcService(adminUser, songService)
        val authorizedService = rpcService.withAuthorization<ISongService>(adminUser)

        authorizedService.setLyrics(UUID.randomUUID(), listOf("lyrics"))
    }

    private fun podcastShow() = PodcastShow(id = UUID.randomUUID(), source = PodcastSource.FEED, title = "Show", createdAt = 0, updatedAt = 0)

    private fun podcastEpisode() = PodcastEpisode(
        id = UUID.randomUUID(),
        showId = UUID.randomUUID(),
        showTitle = "Show",
        guid = "guid",
        title = "Episode",
        publishedAt = 0,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun podcastRpcService(user: User): RpcPodcastService {
        val podcastService = mockk<PodcastService>(relaxed = true)
        val feedService = mockk<PodcastFeedService>(relaxed = true)
        val localScanService = mockk<PodcastLocalScanService>(relaxed = true)
        val importService = mockk<PodcastImportService>(relaxed = true)
        val streamService = mockk<PodcastStreamService>(relaxed = true)

        coEvery { podcastService.getShow(any(), any()) } returns podcastShow()
        coEvery { podcastService.getEpisode(any(), any()) } returns podcastEpisode()
        coEvery { feedService.subscribe(any(), any()) } returns podcastShow()
        coEvery { podcastService.reportPlayback(any(), any()) } returns PodcastEpisodeProgress(
            episodeId = UUID.randomUUID(),
            showId = UUID.randomUUID(),
            positionMs = 0,
            completed = false,
            lastPlayedAt = 0,
            updatedAt = 0,
        )
        coEvery { localScanService.scan(any()) } returns PodcastScanResult(0, 0, 0, 0, 0)

        return RpcPodcastService(
            user,
            podcastService,
            feedService,
            localScanService,
            importService,
            streamService,
            mockk<PodcastIndexService>(relaxed = true),
            mockk<PodcastMaintenanceService>(relaxed = true)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `podcast updateShowSettings importEpisode removeImport and scanLocal require PODCAST_EDIT`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithoutEdit = User(id = UUID.randomUUID(), username = "podcast-noedit", passwordHash = "", isAdmin = false, capabilities = emptyList())
        val authorizedService = podcastRpcService(userWithoutEdit).withAuthorization<IPodcastService>(userWithoutEdit)

        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.updateShowSettings(UUID.randomUUID(), PodcastShowSettings(PodcastDeliveryMode.IMPORT))
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.importEpisode(UUID.randomUUID())
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.removeImport(UUID.randomUUID())
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.scanLocal()
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `podcast PODCAST_EDIT methods are allowed with the capability`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithEdit = User(
            id = UUID.randomUUID(),
            username = "podcast-withedit",
            passwordHash = "",
            isAdmin = false,
            capabilities = listOf(UserCapability.PODCAST_EDIT),
        )
        val authorizedService = podcastRpcService(userWithEdit).withAuthorization<IPodcastService>(userWithEdit)

        authorizedService.updateShowSettings(UUID.randomUUID(), PodcastShowSettings(PodcastDeliveryMode.IMPORT))
        authorizedService.importEpisode(UUID.randomUUID())
        authorizedService.removeImport(UUID.randomUUID())
        authorizedService.scanLocal()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `podcast PODCAST_EDIT methods are allowed for an admin without the capability`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val adminUser = User(id = UUID.randomUUID(), username = "podcast-admin", passwordHash = "", isAdmin = true, capabilities = emptyList())
        val authorizedService = podcastRpcService(adminUser).withAuthorization<IPodcastService>(adminUser)

        authorizedService.updateShowSettings(UUID.randomUUID(), PodcastShowSettings(PodcastDeliveryMode.IMPORT))
        authorizedService.importEpisode(UUID.randomUUID())
        authorizedService.removeImport(UUID.randomUUID())
        authorizedService.scanLocal()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `podcast subscribe and reportPlayback are allowed without PODCAST_EDIT`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithoutEdit = User(id = UUID.randomUUID(), username = "podcast-noedit2", passwordHash = "", isAdmin = false, capabilities = emptyList())
        val authorizedService = podcastRpcService(userWithoutEdit).withAuthorization<IPodcastService>(userWithoutEdit)

        authorizedService.subscribe("https://feed.example/show.xml")
        authorizedService.reportPlayback(EpisodePlaybackReport(episodeId = UUID.randomUUID(), positionMs = 0))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `podcast deleteShow requires admin`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithoutAdmin = User(id = UUID.randomUUID(), username = "podcast-nonadmin", passwordHash = "", isAdmin = false, capabilities = emptyList())
        val authorizedService = podcastRpcService(userWithoutAdmin).withAuthorization<IPodcastService>(userWithoutAdmin)

        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.deleteShow(UUID.randomUUID())
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `podcast deleteShow is allowed for an admin`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val adminUser = User(id = UUID.randomUUID(), username = "podcast-delete-admin", passwordHash = "", isAdmin = true, capabilities = emptyList())
        val authorizedService = podcastRpcService(adminUser).withAuthorization<IPodcastService>(adminUser)

        authorizedService.deleteShow(UUID.randomUUID())
    }

    private fun releaseFixture() = RecentRelease(
        releaseId = UUID.randomUUID(),
        artistId = UUID.randomUUID(),
        artistName = "Artist",
        title = "Title",
        releaseDate = null,
        type = ReleaseType.Album
    )

    private fun releaseRpcService(user: User): RpcReleaseService {
        val releaseService = mockk<ReleaseService>(relaxed = true)
        coEvery { releaseService.confirmRelease(any(), any()) } returns releaseFixture()

        return RpcReleaseService(user, releaseService)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `release setReleaseHidden and confirmRelease require EDIT`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithoutEdit = User(id = UUID.randomUUID(), username = "release-noedit", passwordHash = "", isAdmin = false, capabilities = emptyList())
        val authorizedService = releaseRpcService(userWithoutEdit).withAuthorization<IReleaseService>(userWithoutEdit)

        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.setReleaseHidden(UUID.randomUUID(), true)
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
        assertFailsWith<UnauthorizedException> {
            try {
                authorizedService.confirmRelease(UUID.randomUUID())
            } catch (e: UndeclaredThrowableException) {
                throw e.undeclaredThrowable
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `release setReleaseHidden and confirmRelease are allowed with EDIT`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val userWithEdit = User(id = UUID.randomUUID(), username = "release-withedit", passwordHash = "", isAdmin = false, capabilities = listOf(UserCapability.EDIT))
        val authorizedService = releaseRpcService(userWithEdit).withAuthorization<IReleaseService>(userWithEdit)

        authorizedService.setReleaseHidden(UUID.randomUUID(), true)
        authorizedService.confirmRelease(UUID.randomUUID())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `release setReleaseHidden and confirmRelease are allowed for an admin`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val adminUser = User(id = UUID.randomUUID(), username = "release-admin", passwordHash = "", isAdmin = true, capabilities = emptyList())
        val authorizedService = releaseRpcService(adminUser).withAuthorization<IReleaseService>(adminUser)

        authorizedService.setReleaseHidden(UUID.randomUUID(), true)
        authorizedService.confirmRelease(UUID.randomUUID())
    }

    @Test
    fun `UserCapability fromString parses podcast_edit case-insensitively`() {
        assertEquals(UserCapability.PODCAST_EDIT, UserCapability.fromString("podcast_edit"))
        assertNull(UserCapability.fromString("not-a-capability"))
    }
}
