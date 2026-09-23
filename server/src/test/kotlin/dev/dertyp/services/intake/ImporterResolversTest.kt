package dev.dertyp.services.intake

import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.UserService
import dev.dertyp.services.import.ImportBackend
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ImporterProxy
import dev.dertyp.services.import.Tdn
import dev.dertyp.services.import.Tiddl
import dev.dertyp.services.import.Type
import dev.dertyp.services.import.UpcomingReleaseImportService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.ui.IntakeItem
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImporterResolversTest {
    private val pluginManager = mockk<PluginManager>()
    private val importerProxy = mockk<ImporterProxy>()
    private val importService = mockk<ImportService>()
    private val linkResolver = mockk<LinkResolverService>(relaxed = true)
    private val userService = mockk<UserService>()
    private val environment = mockk<ApplicationEnvironment>(relaxed = true)
    private val upcomingReleases = mockk<UpcomingReleaseImportService>()

    private val tiddl = mockk<IImporter>(relaxed = true) {
        every { id } returns ImportBackend.Tiddl.id
        every { enabled } returns true
    }
    private val tdn = mockk<IImporter>(relaxed = true) {
        every { id } returns ImportBackend.Tdn.id
        every { enabled } returns true
    }

    private val user = User(UUID.randomUUID(), "importer", passwordHash = "")
    private val info = UserInfo.fromUser(user)

    private val appleUrl = "https://music.apple.com/us/album/upcoming/1234"
    private val tidalUrl = "https://tidal.com/browse/album/9"

    private val release = UpcomingReleaseImportService.UpcomingRelease(
        url = appleUrl,
        source = UpcomingReleaseImportService.Source.Apple("1234"),
        title = "Upcoming",
        artists = listOf("Artist"),
        releaseDate = LocalDate.now().plusDays(7),
        trackCount = 12,
    )

    private fun resolvers(): ImporterResolvers {
        every { pluginManager.getAllImporters() } returns listOf(tiddl, tdn)
        every { importerProxy.defaultService } returns ImportBackend.Tiddl
        coEvery { userService.findUserById(user.id) } returns user
        return ImporterResolvers(pluginManager, importerProxy, importService, linkResolver, userService, environment, upcomingReleases)
    }

    private fun resolver(backend: ImportBackend) = resolvers().resolvers().single { it.id == "import.${backend.id}" }

    @Test
    fun `the default resolver offers an upcoming release with its own description and confirmation`() = runBlocking {
        coEvery { importerProxy.resolveImporter(appleUrl, any()) } returns null
        coEvery { upcomingReleases.detect(appleUrl, tiddl) } returns release

        val offer = resolver(ImportBackend.Tiddl).offer(listOf(IntakeItem.Url(appleUrl)), info)

        assertNotNull(offer)
        assertEquals(listOf(IntakeItem.Url(appleUrl)), offer.accepted)
        assertEquals("intake.import.upcomingDescription", offer.descriptionKey)
        assertEquals("intake.import.upcomingConfirm", offer.confirmKey)
    }

    @Test
    fun `a non default resolver ignores an upcoming release`() = runBlocking {
        coEvery { importerProxy.resolveImporter(appleUrl, any()) } returns null

        val offer = resolver(ImportBackend.Tdn).offer(listOf(IntakeItem.Url(appleUrl)), info)

        assertNull(offer)
        coVerify(exactly = 0) { upcomingReleases.detect(any(), tdn) }
    }

    @Test
    fun `submitting an upcoming release resolves the plan and reports the queued tracks`() = runBlocking {
        val plan = mockk<UpcomingReleaseImportService.Plan>()
        coEvery { importerProxy.resolveImporter(appleUrl, any()) } returns null
        coEvery { upcomingReleases.detect(appleUrl, tiddl) } returns release
        coEvery { upcomingReleases.resolve(release) } returns plan
        coEvery { upcomingReleases.submit(plan, tiddl, user) } returns 7

        val offer = resolver(ImportBackend.Tiddl).offer(listOf(IntakeItem.Url(appleUrl)), info)
        val receipt = assertNotNull(offer).submit!!.invoke()

        assertEquals(7, receipt.accepted)
        assertEquals("intake.import.upcomingQueued", receipt.messageKey)
        coVerify(exactly = 1) { upcomingReleases.resolve(release) }
        coVerify(exactly = 1) { upcomingReleases.submit(plan, tiddl, user) }
    }

    @Test
    fun `a routable url keeps the regular import path`() = runBlocking {
        coEvery { importerProxy.resolveImporter(tidalUrl, any()) } returns (tiddl to tidalUrl)
        coEvery { tiddl.parseUrl(tidalUrl) } returns ("9" to Type.ALBUM)
        coEvery { importService.importIds(any(), any(), any(), any(), any()) } returns (true to emptyList())

        val offer = resolver(ImportBackend.Tiddl).offer(listOf(IntakeItem.Url(tidalUrl)), info)
        assertNotNull(offer)
        assertNull(offer.descriptionKey)
        assertNull(offer.confirmKey)

        val receipt = offer.submit!!.invoke()

        assertEquals(1, receipt.accepted)
        assertEquals("importer.queued", receipt.messageKey)
        coVerify(exactly = 0) { upcomingReleases.detect(any(), any()) }
        coVerify(exactly = 1) { importService.importIds(any(), Type.ALBUM, user, ImportBackend.Tiddl.id, any()) }
    }
}
