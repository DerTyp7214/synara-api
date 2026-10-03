package dev.dertyp.services.import

import dev.dertyp.core.process.executeCommand
import dev.dertyp.core.process.findInPath
import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.credentials.ImporterCredentialMaterializer
import dev.dertyp.testing.FakeCredentialProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Path
import java.util.Base64

class BaseImporterCredentialTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
    private lateinit var credentialProvider: FakeCredentialProvider
    private lateinit var authFile: File
    private lateinit var service: TempTiddlService

    @TempDir
    lateinit var tempDir: Path

    private class TempTiddlService(
        indexer: IPluginIndexer,
        storageService: IServerStorageService,
        private val authFile: File,
    ) : TiddlService(indexer, storageService) {
        override fun credentialTargets(): Map<String, File> = mapOf(CredentialFileRoles.TIDDL_AUTH to authFile)

        override fun tokenFileExists(): Boolean = credentialPresent(authFile)
    }

    @BeforeEach
    fun setup() {
        credentialProvider = FakeCredentialProvider()
        startKoin {
            modules(module {
                single<CredentialProvider> { credentialProvider }
                single { ImporterCredentialMaterializer(credentialProvider) }
            })
        }
        mockkStatic("dev.dertyp.core.process.CommandKt")
        every { findInPath("tiddl") } returns "/usr/local/bin/tiddl"
        authFile = tempDir.resolve("tiddl/auth.json").toFile()
        service = TempTiddlService(indexer, storageService, authFile)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun remoteAuth(content: String, fingerprint: String = "fp-1") = ResolvedCredential.Files(
        name = CredentialNames.IMPORTER_TIDDL,
        files = listOf(
            CredentialFile(
                CredentialFileRoles.TIDDL_AUTH,
                Base64.getEncoder().encodeToString(content.toByteArray())
            )
        ),
        fingerprint = fingerprint,
    )

    @Test
    fun `executeImporter materializes the remote files around the command and writes back a rotated token`() =
        runBlocking {
            credentialProvider.put(remoteAuth("""{"token":"old"}"""), managedRemotely = true)
            var seen: String? = null
            coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } answers {
                seen = authFile.readText()
                authFile.writeText("""{"token":"new"}""")
                ProcessExecutionResult(0, "", "")
            }

            service.executeImporter(listOf("tiddl", "download", "url", "x"), { true }, null) {}

            assertEquals("""{"token":"old"}""", seen)
            val writeBack = credentialProvider.writeBacks.single()
            assertEquals(CredentialNames.IMPORTER_TIDDL, writeBack.name)
            assertEquals("fp-1", writeBack.expectedFingerprint)
            assertEquals(CredentialFileRoles.TIDDL_AUTH, writeBack.files.single().role)
            assertEquals(
                """{"token":"new"}""",
                String(Base64.getDecoder().decode(writeBack.files.single().contentBase64))
            )
        }

    @Test
    fun `executeImporter writes nothing back when the command leaves the files untouched`() = runBlocking {
        credentialProvider.put(remoteAuth("""{"token":"same"}"""), managedRemotely = true)
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "", "")

        service.executeImporter(listOf("tiddl", "download", "url", "x"), { true }, null) {}

        assertTrue(authFile.exists())
        assertTrue(credentialProvider.writeBacks.isEmpty())
    }

    @Test
    fun `executeImporter leaves local credentials alone`() = runBlocking {
        credentialProvider.put(remoteAuth("""{"token":"unused"}"""))
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "", "")

        service.executeImporter(listOf("tiddl", "download", "url", "x"), { true }, null) {}

        assertFalse(authFile.exists())
        assertTrue(credentialProvider.resolved.isEmpty())
        coVerify(exactly = 1) { executeCommand(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `login reports the credential server and runs no command when managed remotely`() = runBlocking {
        credentialProvider.put(remoteAuth("""{"token":"old"}"""), managedRemotely = true)
        val lines = mutableListOf<String>()

        val result = service.login({ true }) { lines += it }

        assertEquals(0, result.exitCode)
        assertTrue(lines.single().contains("managed by the credential server"))
        coVerify(exactly = 0) { executeCommand(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `login runs the command for local credentials`() = runBlocking {
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "", "")

        service.login({ true }) {}

        coVerify {
            executeCommand(
                match { it.containsAll(listOf("auth", "login")) },
                any(),
                any(),
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `token presence follows remote availability or the local file`() {
        assertFalse(service.tokenFileExists())

        authFile.parentFile.mkdirs()
        authFile.writeText("{}")
        assertTrue(service.tokenFileExists())

        credentialProvider.markRemote(CredentialNames.IMPORTER_TIDDL)
        assertFalse(service.tokenFileExists())

        credentialProvider.put(remoteAuth("{}"), managedRemotely = true)
        authFile.delete()
        assertTrue(service.tokenFileExists())
    }
}
