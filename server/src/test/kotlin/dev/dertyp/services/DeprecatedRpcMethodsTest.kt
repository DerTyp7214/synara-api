package dev.dertyp.services

import dev.dertyp.core.ClientInfo
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.RecentListens
import dev.dertyp.data.User
import dev.dertyp.services.import.IImportService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.sync.ListenBrainzService
import dev.dertyp.services.sync.RpcListenBrainzService
import dev.dertyp.services.ui.RpcUiService
import dev.dertyp.services.ui.UiService
import dev.dertyp.ui.UiHomeLayout
import dev.dertyp.utils.ResponseShaper
import dev.dertyp.utils.withAuthorization
import dev.dertyp.utils.withCaching
import dev.dertyp.utils.withClientCompat
import dev.dertyp.utils.withLogging
import dev.dertyp.utils.withMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

class DeprecatedRpcMethodsTest {
    private val admin = User(id = UUID.randomUUID(), username = "admin", isAdmin = true)
    private val member = User(id = UUID.randomUUID(), username = "member")
    private val userService = mockk<UserService>()

    init {
        coEvery { userService.findUserById(member.id) } returns member
        coEvery { userService.findUserByUsername("member") } returns member
        coEvery { userService.queryUser(any()) } returns listOf(admin, member)
    }

    private fun wrapped(caller: User): IUserService =
        RpcUserService(caller, userService, mockk(relaxed = true)).wrappedAs<IUserService>(caller)

    private inline fun <reified T : Any> T.wrappedAs(caller: User): T =
        withAuthorization<T>(caller)
            .withLogging<T>()
            .withCaching(T::class.java)
            .withMetrics(T::class.java, caller.username, mockk(relaxed = true))
            .withClientCompat(T::class.java, ResponseShaper(ClientInfo(apiVersion = 1)))

    private suspend fun callByName(service: IUserService, name: String, vararg args: Any?): Any? =
        callByName<IUserService>(service, name, *args)

    private suspend inline fun <reified T : Any> callByName(service: T, name: String, vararg args: Any?): Any? =
        T::class.declaredFunctions.single { it.name == name }.callSuspend(service, *args)

    private suspend inline fun <reified T : Any> collectByName(
        service: T,
        name: String,
        vararg args: Any?
    ): List<Any?> =
        (callByName<T>(service, name, *args) as Flow<*>).toList()

    private fun causes(error: Throwable?): List<Throwable> = generateSequence(error) { it.cause }.toList()

    @OptIn(ExperimentalRpcApi::class)
    @Test
    fun `renamed methods stay rpc callables`() {
        val userCallables = serviceDescriptorOf<IUserService>()
        listOf("findUserById", "findUserByUsername", "getAllUsers", "byId", "byUsername", "allUsers").forEach {
            assertNotNull(userCallables.getCallable(it), it)
        }
        assertNotNull(serviceDescriptorOf<IImportService>().getCallable("getAllImportServices"))
        assertNotNull(serviceDescriptorOf<IMetadataService>().getCallable("getAllMetadataTypes"))
        assertNotNull(serviceDescriptorOf<IArtistService>().getCallable("artistsWithoutMusicBrainzIdFlow"))
        assertNotNull(serviceDescriptorOf<IListenBackupService>().getCallable("getStateFlow"))
        assertNotNull(serviceDescriptorOf<IScheduledTaskConfigurationService>().getCallable("getConfigurationsFlow"))
        assertNotNull(serviceDescriptorOf<IScheduledTaskLogService>().getCallable("getGroupedLogsFlow"))
        assertNotNull(serviceDescriptorOf<IRadioService>().getCallable("radioFlow"))
    }

    @OptIn(ExperimentalRpcApi::class)
    @Test
    fun `deprecated methods with default bodies stay rpc callables`() {
        assertNotNull(serviceDescriptorOf<ISongService>().getCallable("setLiked"))
        assertNotNull(serviceDescriptorOf<IListenBrainzService>().getCallable("getStatusFlow"))
        val scrobbleCallables = serviceDescriptorOf<IScrobbleService>()
        listOf("recentListensFlow", "recentArtistsFlow", "recentAlbumsFlow").forEach {
            assertNotNull(scrobbleCallables.getCallable(it), it)
        }
        assertNotNull(serviceDescriptorOf<IUiService>().getCallable("getHomeCardsFlow"))
    }

    @Test
    fun `the wrapped server services keep their live overrides of defaulted methods`() = runBlocking {
        val songService = mockk<SongService>()
        val songId = UUID.randomUUID()
        val addedAt = Instant.now()
        coEvery { songService.setLikedReturning(songId, admin.id, true, addedAt) } returns null
        callByName<ISongService>(
            SongRpcService(admin, songService).wrappedAs<ISongService>(admin),
            "setLiked",
            songId,
            true,
            addedAt
        )
        coVerify(exactly = 1) { songService.setLikedReturning(songId, admin.id, true, addedAt) }
        coVerify(exactly = 0) { songService.setLikeLevelReturning(any(), any(), any()) }

        val listenBrainzService = mockk<ListenBrainzService>()
        every { listenBrainzService.statusFlow(admin.id) } returns flowOf(null, null)
        assertEquals(
            listOf(null, null),
            collectByName<IListenBrainzService>(
                RpcListenBrainzService(admin, listenBrainzService).wrappedAs<IListenBrainzService>(admin),
                "getStatusFlow"
            )
        )

        val scrobbleService = mockk<ScrobbleService>()
        val recent = RecentListens(nowPlaying = null, recent = emptyList())
        every { scrobbleService.recentListensFlow(admin.id, 5) } returns flowOf(recent, recent)
        every { scrobbleService.recentArtistsFlow(admin.id, 5) } returns flowOf(emptyList(), emptyList())
        every { scrobbleService.recentAlbumsFlow(admin.id, 5) } returns flowOf(emptyList(), emptyList())
        val scrobble = RpcScrobbleService(admin, scrobbleService).wrappedAs<IScrobbleService>(admin)
        assertEquals(listOf(recent, recent), collectByName<IScrobbleService>(scrobble, "recentListensFlow", 5))
        assertEquals(
            listOf(emptyList<Any>(), emptyList<Any>()),
            collectByName<IScrobbleService>(scrobble, "recentArtistsFlow", 5)
        )
        assertEquals(
            listOf(emptyList<Any>(), emptyList<Any>()),
            collectByName<IScrobbleService>(scrobble, "recentAlbumsFlow", 5)
        )

        val uiService = mockk<UiService>()
        val client = ClientInfo(apiVersion = 1)
        val layout = UiHomeLayout(cards = emptyList())
        every { uiService.homeLayoutFlow(admin, client) } returns flowOf(layout, layout)
        assertEquals(
            listOf(layout, layout),
            collectByName<IUiService>(
                RpcUiService(admin, client, null, uiService).wrappedAs<IUiService>(admin),
                "getHomeCardsFlow"
            )
        )
    }

    @Test
    fun `deprecated methods on the wrapped service answer like their replacements`() = runBlocking {
        val service = wrapped(admin)

        assertEquals(service.byId(member.id), callByName(service, "findUserById", member.id))
        assertEquals(service.byUsername("member"), callByName(service, "findUserByUsername", "member"))
        assertEquals(service.allUsers(), callByName(service, "getAllUsers"))
        assertEquals(listOf(admin, member), callByName(service, "getAllUsers"))
    }

    @Test
    fun `an admin only deprecated method still rejects other users`() = runBlocking {
        val service = wrapped(member)

        val deprecated = runCatching { callByName(service, "getAllUsers") }.exceptionOrNull()
        val replacement = runCatching { service.allUsers() }.exceptionOrNull()

        assertTrue(
            causes(deprecated).any { it is UnauthorizedException },
            "deprecated call failed with ${causes(deprecated)}"
        )
        assertTrue(
            causes(replacement).any { it is UnauthorizedException },
            "replacement call failed with ${causes(replacement)}"
        )
        assertEquals(member, callByName(service, "findUserById", member.id))
    }
}
