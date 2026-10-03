package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.CredentialInput
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.OAuthAuthStyle
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.TidalSessionFormat
import dev.dertyp.credentials.WriteBackRequest
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CredentialResolverTest {
    private val resolver = CredentialResolver(FakeSecretRepository(), MockUpstream { json("{}") }.client)
    private val customName = "custom.tidal"

    private fun cookies(expirySeconds: Long, name: String = FileBroker.MEDIA_USER_TOKEN) = """
        # Netscape HTTP Cookie File
        .apple.com	TRUE	/	FALSE	1999999999	other	x
        #HttpOnly_.music.apple.com	TRUE	/	TRUE	$expirySeconds	$name	token-value
    """.trimIndent()

    private fun cookieSecret(text: String) = FileSecret(
        listOf(
            FileContents.encode(CredentialFileRoles.GAMDL_COOKIES, text),
            CredentialFile(CredentialFileRoles.GAMDL_WVD, Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))),
        ),
    )

    @Test
    fun `cookie expiry is parsed from the media user token`() {
        val expiry = System.currentTimeMillis() / 1000 + 60L * 24 * 3600

        val state = FileBroker.stateOf(cookieSecret(cookies(expiry)))

        assertEquals(CredentialStatus.OK, state.status)
        assertEquals(expiry * 1000, state.expiresAt)
    }

    @Test
    fun `cookies expiring within a week are expiring`() {
        val expiry = System.currentTimeMillis() / 1000 + 3L * 24 * 3600

        assertEquals(CredentialStatus.EXPIRING, FileBroker.stateOf(cookieSecret(cookies(expiry))).status)
    }

    @Test
    fun `past cookies are expired and missing cookies are errors`() {
        val expiry = System.currentTimeMillis() / 1000 - 60

        assertEquals(CredentialStatus.EXPIRED, FileBroker.stateOf(cookieSecret(cookies(expiry))).status)
        assertEquals(CredentialStatus.ERROR, FileBroker.stateOf(cookieSecret(cookies(expiry, "other-cookie"))).status)
    }

    @Test
    fun `file credentials resolve with a content fingerprint`() = runTest {
        val expiry = System.currentTimeMillis() / 1000 + 3L * 24 * 3600
        val secret = cookieSecret(cookies(expiry))
        val repository = FakeSecretRepository().apply { put(CredentialNames.IMPORTER_GAMDL, secret) }
        val resolver = CredentialResolver(repository, MockUpstream { json("{}") }.client)

        val files = assertIs<ResolvedCredential.Files>(resolver.resolve(CredentialNames.IMPORTER_GAMDL))

        val concatenated = secret.files.map { Base64.getDecoder().decode(it.contentBase64) }.reduce { a, b -> a + b }
        assertEquals(Fingerprints.sha256Hex(concatenated), files.fingerprint)
        assertEquals(secret.files, files.files)
        assertEquals(expiry * 1000, files.expiresAt)
        assertEquals(CredentialStatus.EXPIRING, repository.states[CredentialNames.IMPORTER_GAMDL]?.status)
    }

    @Test
    fun `file write back is compare and set`() = runTest {
        val secret = cookieSecret(cookies(System.currentTimeMillis() / 1000 + 60L * 24 * 3600))
        val repository = FakeSecretRepository().apply { put(CredentialNames.IMPORTER_GAMDL, secret) }
        val resolver = CredentialResolver(repository, MockUpstream { json("{}") }.client)
        val newCookies = FileContents.encode(
            CredentialFileRoles.GAMDL_COOKIES,
            cookies(System.currentTimeMillis() / 1000 + 90L * 24 * 3600)
        )

        val conflict = assertFailsWith<CredentialException> {
            resolver.writeBack(CredentialNames.IMPORTER_GAMDL, WriteBackRequest("stale", listOf(newCookies)))
        }
        assertEquals(CredentialErrorCode.CONFLICT, conflict.code)

        val result = resolver.writeBack(
            CredentialNames.IMPORTER_GAMDL,
            WriteBackRequest(Fingerprints.ofFiles(secret.files), listOf(newCookies)),
        )
        val stored = assertIs<FileSecret>(repository.secrets[CredentialNames.IMPORTER_GAMDL])
        assertEquals(listOf(newCookies, secret.files[1]), stored.files)
        assertEquals(Fingerprints.ofFiles(stored.files), result.fingerprint)
    }

    @Test
    fun `blank secret fields keep the existing values`() {
        val existing = OAuthSecret("id", "secret", "https://a/token", OAuthAuthStyle.BASIC, "s")

        val updated = resolver.toStoredSecret(
            customName,
            CredentialKind.OAUTH_CLIENT_CREDENTIALS,
            CredentialInput.OAuthClientCredentialsInput("", "", "https://b/token", OAuthAuthStyle.FORM),
            existing,
        )

        assertEquals(OAuthSecret("id", "secret", "https://b/token", OAuthAuthStyle.FORM, null), updated)

        val pair = resolver.toStoredSecret(
            customName,
            CredentialKind.API_KEY_PAIR,
            CredentialInput.ApiKeyPairInput("new-key", ""),
            ApiKeyPairSecret("old-key", "old-secret"),
        )
        assertEquals(ApiKeyPairSecret("new-key", "old-secret"), pair)
    }

    @Test
    fun `blank secret fields without an existing value are invalid`() {
        val error = assertFailsWith<CredentialException> {
            resolver.toStoredSecret(customName, CredentialKind.API_KEY, CredentialInput.ApiKeyInput(""), null)
        }
        assertEquals(CredentialErrorCode.INVALID, error.code)
    }

    @Test
    fun `input of another kind is rejected`() {
        val error = assertFailsWith<CredentialException> {
            resolver.toStoredSecret(customName, CredentialKind.API_KEY_PAIR, CredentialInput.ApiKeyInput("k"), null)
        }
        assertEquals(CredentialErrorCode.INVALID, error.code)
    }

    @Test
    fun `invalid apple keys are rejected`() {
        val error = assertFailsWith<CredentialException> {
            resolver.toStoredSecret(
                customName,
                CredentialKind.APPLE_DEVELOPER_KEY,
                CredentialInput.AppleDeveloperKeyInput(
                    "team",
                    "key",
                    "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
                ),
                null,
            )
        }
        assertEquals(CredentialErrorCode.INVALID, error.code)
    }

    @Test
    fun `tidal session input keeps tokens and imports an auth file`() {
        val existing = TidalSessionSecret(
            TidalSessionFormat.TIDDL, "cid", "csecret",
            accessToken = "a", refreshToken = "r", expiresAt = 5, userId = "1", countryCode = "DE",
        )

        val kept = assertIs<TidalSessionSecret>(
            resolver.toStoredSecret(
                customName,
                CredentialKind.TIDAL_DEVICE_SESSION,
                CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "", ""),
                existing,
            ),
        )
        assertEquals(existing, kept)

        val imported = assertIs<TidalSessionSecret>(
            resolver.toStoredSecret(
                customName,
                CredentialKind.TIDAL_DEVICE_SESSION,
                CredentialInput.TidalSessionInput(
                    TidalSessionFormat.TIDDL, "new-cid", "",
                    """{"token":"a2","refresh_token":"r2","expires_at":10,"user_id":"2","country_code":"US"}""",
                ),
                existing,
            ),
        )
        assertEquals("new-cid", imported.clientId)
        assertEquals("csecret", imported.clientSecret)
        assertEquals("r2", imported.refreshToken)
        assertEquals(10_000L, imported.expiresAt)
        assertEquals("US", imported.countryCode)

        val fresh = assertIs<TidalSessionSecret>(
            resolver.toStoredSecret(
                customName,
                CredentialKind.TIDAL_DEVICE_SESSION,
                CredentialInput.TidalSessionInput(TidalSessionFormat.TDN, "cid", "secret"),
                null,
            ),
        )
        assertNull(fresh.refreshToken)
        assertEquals(CredentialStatus.NEEDS_LOGIN, resolver.initialState(fresh).status)
    }

    @Test
    fun `blank tidal clients of the importer presets use the default client`() {
        for (name in listOf(CredentialNames.IMPORTER_TIDDL, CredentialNames.IMPORTER_TDN)) {
            val fresh = assertIs<TidalSessionSecret>(
                resolver.toStoredSecret(
                    name,
                    CredentialKind.TIDAL_DEVICE_SESSION,
                    CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "", ""),
                    null,
                ),
            )
            assertEquals(CredentialPresets.TIDAL_IMPORTER_CLIENT_ID, fresh.clientId)
            assertEquals(CredentialPresets.TIDAL_IMPORTER_CLIENT_SECRET, fresh.clientSecret)
        }
    }

    @Test
    fun `typed and existing tidal clients win over the preset default`() {
        val typed = assertIs<TidalSessionSecret>(
            resolver.toStoredSecret(
                CredentialNames.IMPORTER_TIDDL,
                CredentialKind.TIDAL_DEVICE_SESSION,
                CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "typed-id", "typed-secret"),
                TidalSessionSecret(TidalSessionFormat.TIDDL, "stored-id", "stored-secret"),
            ),
        )
        assertEquals("typed-id", typed.clientId)
        assertEquals("typed-secret", typed.clientSecret)

        val stored = assertIs<TidalSessionSecret>(
            resolver.toStoredSecret(
                CredentialNames.IMPORTER_TIDDL,
                CredentialKind.TIDAL_DEVICE_SESSION,
                CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "", ""),
                TidalSessionSecret(TidalSessionFormat.TIDDL, "stored-id", "stored-secret"),
            ),
        )
        assertEquals("stored-id", stored.clientId)
        assertEquals("stored-secret", stored.clientSecret)
    }

    @Test
    fun `blank tidal clients without a preset are invalid`() {
        val error = assertFailsWith<CredentialException> {
            resolver.toStoredSecret(
                customName,
                CredentialKind.TIDAL_DEVICE_SESSION,
                CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "", ""),
                null,
            )
        }
        assertEquals(CredentialErrorCode.INVALID, error.code)
    }

    @Test
    fun `file input keeps blank and absent roles from the existing secret`() {
        val existing = cookieSecret(cookies(1999999999))
        val wvd = CredentialFile(CredentialFileRoles.GAMDL_WVD, Base64.getEncoder().encodeToString(byteArrayOf(9)))

        val updated = assertIs<FileSecret>(
            resolver.toStoredSecret(
                customName,
                CredentialKind.FILE,
                CredentialInput.FileInput(listOf(CredentialFile(CredentialFileRoles.GAMDL_COOKIES, ""), wvd)),
                existing,
            ),
        )
        assertEquals(listOf(existing.files[0], wvd), updated.files)

        val untouched = assertIs<FileSecret>(
            resolver.toStoredSecret(customName, CredentialKind.FILE, CredentialInput.FileInput(listOf(wvd)), existing),
        )
        assertEquals(listOf(existing.files[0], wvd), untouched.files)

        val error = assertFailsWith<CredentialException> {
            resolver.toStoredSecret(
                customName,
                CredentialKind.FILE,
                CredentialInput.FileInput(listOf(CredentialFile(CredentialFileRoles.GAMDL_WVD, "not base64!"))),
                null,
            )
        }
        assertEquals(CredentialErrorCode.INVALID, error.code)
    }

    @Test
    fun `presets cover the core credentials`() {
        val presets = resolver.presets().associateBy { it.name }

        assertEquals(OAuthAuthStyle.BASIC, presets[CredentialNames.TIDAL_API]?.authStyle)
        assertEquals(TidalAuthApi.TOKEN_URL, presets[CredentialNames.TIDAL_API]?.tokenUrl)
        assertEquals(OAuthAuthStyle.FORM, presets[CredentialNames.SPOTIFY_API]?.authStyle)
        assertEquals(TidalSessionFormat.TDN, presets[CredentialNames.IMPORTER_TDN]?.format)
        assertEquals(
            listOf(CredentialFileRoles.GAMDL_COOKIES, CredentialFileRoles.GAMDL_WVD),
            presets[CredentialNames.IMPORTER_GAMDL]?.fileRoles,
        )
        assertEquals(CredentialNames.CORE.toSet(), presets.keys)
    }
}
