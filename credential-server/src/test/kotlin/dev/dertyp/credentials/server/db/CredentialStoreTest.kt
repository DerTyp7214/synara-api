package dev.dertyp.credentials.server.db

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.CredentialServerDeps
import dev.dertyp.credentials.server.broker.CredentialException
import dev.dertyp.credentials.server.broker.CredentialStateUpdate
import dev.dertyp.credentials.server.broker.StoredSecret
import dev.dertyp.credentials.server.crypto.SecretBox
import dev.dertyp.credentials.server.crypto.SecretHasher
import dev.dertyp.credentials.server.testDeps
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class CredentialStoreTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var deps: CredentialServerDeps
    private lateinit var database: Database
    private val store get() = deps.store
    private val ok = CredentialStateUpdate(CredentialStatus.OK, null, null, null)

    @BeforeEach
    fun setUp() {
        deps = testDeps(dir)
        database = Database.connect(deps.dataSource)
    }

    @AfterEach
    fun tearDown() {
        deps.close()
    }

    private fun apiKey(name: String, key: String = "key-$name") =
        store.upsertCredential(name, CredentialKind.API_KEY, "desc", StoredSecret.ApiKeySecret(key), ok)

    private fun version(ref: String) = store.client(ref)!!.tokenVersion

    @Test
    fun `creates, reads, updates and deletes clients`() {
        apiKey("youtube.api")
        val created = store.createClient("synara-main", listOf(GrantSpec("youtube.api", writeBack = true)))
        assertTrue(created.client.clientId.startsWith(SecretHasher.CLIENT_ID_PREFIX))
        assertTrue(created.clientSecret.startsWith(SecretHasher.SECRET_PREFIX))
        assertEquals(listOf(GrantInfo("youtube.api", CredentialKind.API_KEY, writeBack = true)), created.client.grants)
        assertEquals(created.client, store.client(created.client.id))
        assertEquals(created.client, store.client(created.client.clientId))

        val record = store.clientRecordByClientId(created.client.clientId)!!
        assertTrue(SecretHasher.matches(created.clientSecret, record.secretHash))

        val renamed = store.updateClient(created.client.clientId, UpdateClientRequest(name = "renamed"))
        assertEquals("renamed", renamed.name)
        assertEquals(created.client.tokenVersion, renamed.tokenVersion)
        assertEquals(listOf(renamed), store.listClients())

        assertTrue(store.deleteClient(created.client.id))
        assertFalse(store.deleteClient(created.client.id))
        assertNull(store.client(created.client.id))
        assertEquals(emptyList<String>(), store.credential("youtube.api")!!.grantedTo)
    }

    @Test
    fun `every grant, enabled, secret change and revoke bumps the token version`() {
        apiKey("a.api")
        apiKey("b.api")
        val ref = store.createClient("c").client.clientId
        var expected = version(ref)

        store.grant(ref, "a.api", writeBack = false)
        assertEquals(++expected, version(ref))
        store.grant(ref, "b.api", writeBack = true)
        assertEquals(++expected, version(ref))
        assertEquals(
            listOf(GrantInfo("a.api", CredentialKind.API_KEY), GrantInfo("b.api", CredentialKind.API_KEY, true)),
            store.client(ref)!!.grants,
        )
        store.ungrant(ref, "a.api")
        assertEquals(++expected, version(ref))
        store.setGrants(ref, listOf(GrantSpec("a.api"), GrantSpec("b.api")))
        assertEquals(++expected, version(ref))
        store.updateClient(ref, UpdateClientRequest(enabled = false))
        assertEquals(++expected, version(ref))
        store.updateClient(ref, UpdateClientRequest(enabled = false))
        assertEquals(expected, version(ref))
        store.updateClient(ref, UpdateClientRequest(enabled = true))
        assertEquals(++expected, version(ref))
        val rotated = store.rotateSecret(ref)
        assertEquals(++expected, rotated.client.tokenVersion)
        store.revokeTokens(ref)
        assertEquals(++expected, version(ref))
        store.deleteCredential("a.api")
        assertEquals(++expected, version(ref))
        assertEquals(listOf("b.api"), store.client(ref)!!.grants.map { it.name })
    }

    @Test
    fun `granting an unknown credential fails without side effects`() {
        apiKey("a.api")
        val ref = store.createClient("c", listOf(GrantSpec("a.api"))).client.clientId
        val before = version(ref)
        val error = assertThrows<CredentialException> { store.setGrants(ref, listOf(GrantSpec("missing.api"))) }
        assertEquals(CredentialErrorCode.NOT_FOUND, error.code)
        assertEquals(before, version(ref))
        assertEquals(listOf("a.api"), store.client(ref)!!.grants.map { it.name })
        assertThrows<CredentialException> { store.createClient("d", listOf(GrantSpec("missing.api"))) }
    }

    @Test
    fun `deleting rows cascades to grants`() {
        apiKey("a.api")
        apiKey("b.api")
        val first = store.createClient("first", listOf(GrantSpec("a.api"), GrantSpec("b.api"))).client
        val second = store.createClient("second", listOf(GrantSpec("a.api"))).client
        assertEquals(3L, transaction(database) { GrantTable.selectAll().count() })

        store.deleteCredential("a.api")
        assertEquals(1L, transaction(database) { GrantTable.selectAll().count() })
        assertEquals(emptyList<GrantInfo>(), store.client(second.id)!!.grants)

        store.deleteClient(first.id)
        assertEquals(0L, transaction(database) { GrantTable.selectAll().count() })
    }

    @Test
    fun `secrets are stored encrypted and bound to their name`() {
        apiKey("a.api", "plain-key-a")
        apiKey("b.api", "plain-key-b")
        val stored = transaction(database) {
            CredentialTable.selectAll().where { CredentialTable.name eq "a.api" }.single()[CredentialTable.secret]
        }
        assertTrue(stored.startsWith(SecretBox.PREFIX))
        assertFalse(stored.contains("plain-key-a"))
        assertEquals(StoredSecret.ApiKeySecret("plain-key-a"), store.loadSecret("a.api"))
        assertEquals(CredentialKind.API_KEY, store.kind("a.api"))
        assertNull(store.kind("missing"))
        assertNull(store.loadSecret("missing"))

        transaction(database) {
            CredentialTable.update({ CredentialTable.name eq "b.api" }) { it[secret] = stored }
        }
        assertThrows<Exception> { store.loadSecret("b.api") }
    }

    @Test
    fun `save secret creates or updates and state updates touch only state`() {
        val tidal = StoredSecret.TidalSessionSecret(TidalSessionFormat.TIDDL, "id", "secret", refreshToken = "r1")
        store.saveSecret("importer.tiddl", tidal, CredentialStateUpdate(CredentialStatus.OK, null, 10L, "fp1"))
        val created = store.credential("importer.tiddl")!!
        assertEquals(CredentialKind.TIDAL_DEVICE_SESSION, created.kind)
        assertEquals(10L, created.expiresAt)

        store.saveSecret(
            "importer.tiddl",
            tidal.copy(refreshToken = "r2"),
            CredentialStateUpdate(CredentialStatus.EXPIRING, "soon", 20L, "fp2")
        )
        assertEquals("r2", (store.loadSecret("importer.tiddl") as StoredSecret.TidalSessionSecret).refreshToken)

        store.updateState("importer.tiddl", CredentialStateUpdate(CredentialStatus.NEEDS_LOGIN, "login", null, "fp2"))
        val summary = store.credential("importer.tiddl")!!
        assertEquals(CredentialStatus.NEEDS_LOGIN, summary.status)
        assertEquals("login", summary.statusMessage)
        assertNull(summary.expiresAt)
        assertEquals("r2", (store.loadSecret("importer.tiddl") as StoredSecret.TidalSessionSecret).refreshToken)
        assertEquals(listOf("importer.tiddl"), store.listCredentials().map { it.name })
    }

    @Test
    fun `touch grant and token issue record usage`() {
        apiKey("a.api")
        val created = store.createClient("c", listOf(GrantSpec("a.api"))).client
        val id = UUID.fromString(created.id)
        assertNull(created.lastTokenAt)
        store.markTokenIssued(id)
        store.touchGrant(id, "a.api")
        assertTrue(store.client(created.id)!!.lastTokenAt != null)
        val lastUsed = transaction(database) { GrantTable.selectAll().single()[GrantTable.lastUsedAt] }
        assertTrue(lastUsed != null)
    }
}
