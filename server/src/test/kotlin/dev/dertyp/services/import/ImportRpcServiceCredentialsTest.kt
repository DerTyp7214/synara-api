package dev.dertyp.services.import

import dev.dertyp.data.User
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import io.ktor.server.application.ApplicationCall
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImportRpcServiceCredentialsTest {

    private fun rpcWith(importer: IImporter?): ImportRpcService {
        val pm = mockk<PluginManager> {
            every { getImporter(any()) } returns importer
        }
        val importService = mockk<ImportService> {
            every { pluginManager } returns pm
        }
        return ImportRpcService(
            user = mockk<User>(),
            call = mockk<ApplicationCall>(),
            importService = importService,
            importerProxy = mockk(),
        )
    }

    @Test
    fun `setImportCredentials forwards to an importer that declares CREDENTIALS`() = runBlocking {
        val importer = mockk<IImporter>(relaxed = true) {
            every { capabilities } returns setOf(ImporterCapability.CREDENTIALS)
        }
        val rpc = rpcWith(importer)

        val creds = GamdlCredentials(cookiesTxt = "netscape-cookies")
        rpc.setImportCredentials(ImportBackend("gamdl"), creds)

        coVerify(exactly = 1) { importer.provideCredentials(creds) }
    }

    @Test
    fun `setImportCredentials rejects an importer without the CREDENTIALS capability`() {
        val importer = mockk<IImporter>(relaxed = true) {
            every { capabilities } returns emptySet()
        }
        val rpc = rpcWith(importer)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { rpc.setImportCredentials(ImportBackend("tiddl"), GamdlCredentials("x")) }
        }
        coVerify(exactly = 0) { importer.provideCredentials(any()) }
    }

    @Test
    fun `setImportCredentials throws for an unknown backend`() {
        val rpc = rpcWith(importer = null)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { rpc.setImportCredentials(ImportBackend("nope"), GamdlCredentials("x")) }
        }
    }

    @Test
    fun `getImporterCapabilities maps each importer id to its declared capabilities`() = runBlocking {
        val gamdl = mockk<IImporter> {
            every { id } returns "gamdl"
            every { capabilities } returns setOf(ImporterCapability.IMPORT_SONG, ImporterCapability.CREDENTIALS)
        }
        val tiddl = mockk<IImporter> {
            every { id } returns "tiddl"
            every { capabilities } returns setOf(ImporterCapability.LOGIN, ImporterCapability.FAVORITES)
        }
        val pm = mockk<PluginManager> {
            every { getAllImporters() } returns listOf(gamdl, tiddl)
        }
        val importService = mockk<ImportService> {
            every { pluginManager } returns pm
        }
        val rpc = ImportRpcService(
            user = mockk<User>(),
            call = mockk<ApplicationCall>(),
            importService = importService,
            importerProxy = mockk(),
        )

        val caps = rpc.getImporterCapabilities()

        assertEquals(setOf(ImporterCapability.IMPORT_SONG, ImporterCapability.CREDENTIALS), caps["gamdl"])
        assertEquals(setOf(ImporterCapability.LOGIN, ImporterCapability.FAVORITES), caps["tiddl"])
    }
}
