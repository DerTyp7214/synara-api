package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImporterCredentialMaterializerTest {
    @TempDir
    lateinit var dir: File

    private val name = CredentialNames.IMPORTER_TIDDL
    private val authJson = """{"token":"a","refresh_token":"r1"}"""

    private fun files(content: String = authJson) = ResolvedCredential.Files(
        name,
        listOf(CredentialFile(CredentialFileRoles.TIDDL_AUTH, Base64.encode(content.toByteArray()))),
        "fp-1",
    )

    private fun provider(managed: Boolean = true, writeBackResult: Boolean = true) = mockk<CredentialProvider>().also {
        every { it.isManagedRemotely(any()) } returns managed
        coEvery { it.resolve(name) } answers { files() }
        coEvery { it.writeBack(any(), any(), any()) } returns writeBackResult
    }

    private fun target() = File(dir, "nested/.tiddl/auth.json")

    @Test
    fun `local credentials run the block untouched`() = runBlocking {
        val provider = provider(managed = false)
        val materializer = ImporterCredentialMaterializer(provider)

        val result = materializer.withFiles(name, mapOf(CredentialFileRoles.TIDDL_AUTH to target())) { "ran" }
        val withoutName = materializer.withFiles(null, mapOf(CredentialFileRoles.TIDDL_AUTH to target())) { "ran too" }

        assertEquals("ran", result)
        assertEquals("ran too", withoutName)
        assertFalse(target().exists())
        coVerify(exactly = 0) { provider.resolve(any()) }
        coVerify(exactly = 0) { provider.writeBack(any(), any(), any()) }
    }

    @Test
    fun `remote files are written with owner only permissions before the block runs`() = runBlocking {
        val materializer = ImporterCredentialMaterializer(provider())
        var seen: String? = null

        materializer.withFiles(name, mapOf(CredentialFileRoles.TIDDL_AUTH to target())) { seen = target().readText() }

        assertEquals(authJson, seen)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target().toPath())))
    }

    @Test
    fun `unchanged files are not written back`() = runBlocking {
        val provider = provider()
        ImporterCredentialMaterializer(provider).withFiles(name, mapOf(CredentialFileRoles.TIDDL_AUTH to target())) { }

        coVerify(exactly = 0) { provider.writeBack(any(), any(), any()) }
    }

    @Test
    fun `changed files are written back with the fingerprint they were based on`() = runBlocking {
        val provider = provider()
        val written = slot<List<CredentialFile>>()
        coEvery { provider.writeBack(name, "fp-1", capture(written)) } returns true
        val rotated = """{"token":"b","refresh_token":"r2"}"""

        ImporterCredentialMaterializer(provider).withFiles(
            name,
            mapOf(CredentialFileRoles.TIDDL_AUTH to target(), CredentialFileRoles.TDN_TOKEN to File(dir, "untouched.json")),
        ) { target().writeText(rotated) }

        assertEquals(1, written.captured.size)
        assertEquals(CredentialFileRoles.TIDDL_AUTH, written.captured.single().role)
        assertEquals(rotated, Base64.decode(written.captured.single().contentBase64).decodeToString())
    }

    @Test
    fun `files changed by a failing block are still written back`() {
        val provider = provider(writeBackResult = false)

        assertThrows<IllegalStateException> {
            runBlocking {
                ImporterCredentialMaterializer(provider).withFiles(name, mapOf(CredentialFileRoles.TIDDL_AUTH to target())) {
                    target().writeText("{}")
                    throw IllegalStateException("cli failed")
                }
            }
        }
        coVerify(exactly = 1) { provider.writeBack(name, "fp-1", any()) }
    }

    @Test
    fun `an unavailable remote credential fails before the block runs`() {
        val provider = provider()
        coEvery { provider.resolve(name) } returns null
        var ran = false

        assertThrows<CredentialUnavailableException> {
            runBlocking { ImporterCredentialMaterializer(provider).withFiles(name, mapOf(CredentialFileRoles.TIDDL_AUTH to target())) { ran = true } }
        }
        assertFalse(ran)
    }

    @Test
    fun `runs for the same name are serialized`() = runBlocking {
        val materializer = ImporterCredentialMaterializer(provider())
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        (1..4).map {
            async(Dispatchers.Default) {
                materializer.withFiles(name, mapOf(CredentialFileRoles.TIDDL_AUTH to target())) {
                    maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxActive.get())
        assertTrue(target().exists())
    }
}
