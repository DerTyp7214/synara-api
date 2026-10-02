package dev.dertyp.credentials.server.cli

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.CredentialServerDeps
import dev.dertyp.credentials.server.credentialServerModule
import dev.dertyp.credentials.server.jsonClient
import dev.dertyp.credentials.server.testDeps
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.encoding.Base64

class CredentialCliTest {
    @TempDir
    lateinit var dir: Path

    private class Captured(val code: Int, val out: String, val err: String)

    private fun CredentialServerDeps.cli(vararg args: String): Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = CredentialCli(this, PrintStream(out, true), PrintStream(err, true)).run(args.toList())
        return Captured(code, out.toString(), err.toString())
    }

    @Test
    fun `bootstraps a client that can exchange a token and fetch a credential`() = testDeps(dir).use { deps ->
        assertEquals(0, deps.cli("credentials", "set-api-key", "youtube.api", "yt-key").code)
        assertEquals(0, deps.cli("credentials", "set-key-pair", "podcastindex.api", "pi-key", "pi-secret").code)
        val cookies = dir.resolve("cookies.txt").also { Files.writeString(it, "# Netscape HTTP Cookie File\n") }
        assertEquals(0, deps.cli("credentials", "import-file", "importer.gamdl", "--role", "cookies.txt", "--file", cookies.toString()).code)

        val created = deps.cli("clients", "create", "synara-main", "--grant", "youtube.api", "--grant", "importer.gamdl:w")
        assertEquals(0, created.code)
        val clientId = Regex("clientId:\\s+(\\S+)").find(created.out)!!.groupValues[1]
        val secret = Regex("clientSecret:\\s+(\\S+)").find(created.out)!!.groupValues[1]

        assertEquals(0, deps.cli("clients", "grant", clientId, "podcastindex.api").code)
        val listed = deps.cli("clients", "list")
        assertTrue(listed.out.contains("importer.gamdl:w"))
        assertTrue(listed.out.contains("podcastindex.api"))
        assertFalse(listed.out.contains(secret))
        assertTrue(deps.cli("credentials", "list").out.contains("youtube.api"))
        assertFalse(deps.cli("credentials", "list").out.contains("yt-key"))
        assertEquals(0, deps.cli("credentials", "test", "youtube.api").code)

        testApplication {
            application { credentialServerModule(deps) }
            val client = jsonClient()
            val token = client.post(CredentialProtocol.TOKEN_PATH) {
                contentType(ContentType.Application.Json)
                setBody(TokenRequest(clientId, secret))
            }.body<TokenResponse>()
            assertEquals(setOf("youtube.api", "importer.gamdl", "podcastindex.api"), token.grants.map { it.name }.toSet())

            val apiKey = client.get(CredentialProtocol.credentialPath("youtube.api")) { bearerAuth(token.accessToken) }
            assertEquals(ResolvedCredential.ApiKey("youtube.api", "yt-key"), apiKey.body<ResolvedCredential>())
            val pair = client.get(CredentialProtocol.credentialPath("podcastindex.api")) { bearerAuth(token.accessToken) }
            assertEquals(ResolvedCredential.ApiKeyPair("podcastindex.api", "pi-key", "pi-secret"), pair.body<ResolvedCredential>())
            val files = client.get(CredentialProtocol.credentialPath("importer.gamdl")) { bearerAuth(token.accessToken) }
                .body<ResolvedCredential>() as ResolvedCredential.Files
            assertEquals("# Netscape HTTP Cookie File\n", Base64.decode(files.files.single().contentBase64).decodeToString())

            assertEquals(0, deps.cli("clients", "revoke-tokens", clientId).code)
            val revoked = client.get(CredentialProtocol.credentialPath("youtube.api")) { bearerAuth(token.accessToken) }
            assertEquals(HttpStatusCode.Unauthorized, revoked.status)
        }

        assertEquals(0, deps.cli("clients", "disable", clientId).code)
        assertTrue(deps.cli("clients", "list").out.contains("disabled"))
        assertEquals(0, deps.cli("keys", "rotate").code)
        assertEquals(0, deps.cli("credentials", "delete", "podcastindex.api").code)
        assertEquals(1, deps.cli("credentials", "delete", "podcastindex.api").code)
        assertEquals(0, deps.cli("clients", "delete", clientId).code)
        assertTrue(deps.cli("clients", "list").out.isBlank())
    }

    @Test
    fun `reports usage errors`() = testDeps(dir).use { deps ->
        assertEquals(2, deps.cli("clients", "create").code)
        assertEquals(2, deps.cli("credentials", "set-oauth", "spotify.api", "--client-id", "a").code)
        assertEquals(2, deps.cli("nope").code)
        val missing = deps.cli("clients", "rotate", "syn_missing")
        assertEquals(1, missing.code)
        assertTrue(missing.err.contains("NOT_FOUND"))
        assertTrue(CredentialCli.isCommand(arrayOf("clients", "list")))
        assertFalse(CredentialCli.isCommand(arrayOf("-config=application.yaml")))
        assertFalse(CredentialCli.isCommand(emptyArray()))
    }
}
