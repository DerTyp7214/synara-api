package dev.dertyp.services

import dev.dertyp.core.ClientInfo
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.User
import dev.dertyp.services.import.IImportService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.utils.ResponseShaper
import dev.dertyp.utils.withAuthorization
import dev.dertyp.utils.withCaching
import dev.dertyp.utils.withClientCompat
import dev.dertyp.utils.withLogging
import dev.dertyp.utils.withMetrics
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        RpcUserService(caller, userService, mockk(relaxed = true))
            .withAuthorization<IUserService>(caller)
            .withLogging<IUserService>()
            .withCaching(IUserService::class.java)
            .withMetrics(IUserService::class.java, caller.username, mockk(relaxed = true))
            .withClientCompat(IUserService::class.java, ResponseShaper(ClientInfo(apiVersion = 1)))

    private suspend fun callByName(service: IUserService, name: String, vararg args: Any?): Any? =
        IUserService::class.declaredFunctions.single { it.name == name }.callSuspend(service, *args)

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
