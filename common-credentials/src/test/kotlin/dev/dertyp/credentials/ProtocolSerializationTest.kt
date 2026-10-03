package dev.dertyp.credentials

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolSerializationTest {
    private val json = CredentialJson.json
    private val file = CredentialFile("auth.json", "e30=")

    private fun discriminator(encoded: String) =
        Json.parseToJsonElement(encoded).jsonObject.getValue("type").jsonPrimitive.content

    @Test
    fun resolvedCredentialVariantsRoundTrip() {
        val cases = listOf<Pair<ResolvedCredential, String>>(
            ResolvedCredential.AccessToken("tidal.api", "tok", "Bearer", 1000L) to "access_token",
            ResolvedCredential.DeveloperToken("applemusic.developer", "jwt", 2000L) to "developer_token",
            ResolvedCredential.ApiKey("youtube.api", "k") to "api_key",
            ResolvedCredential.ApiKeyPair("acoustid.api", "k", "s") to "api_key_pair",
            ResolvedCredential.Files("importer.tiddl", listOf(file), "fp") to "files",
        )
        for ((value, tag) in cases) {
            val encoded = json.encodeToString<ResolvedCredential>(value)
            assertEquals(tag, discriminator(encoded))
            assertEquals(value, json.decodeFromString<ResolvedCredential>(encoded))
        }
    }

    @Test
    fun credentialInputVariantsRoundTrip() {
        val cases = listOf<Pair<CredentialInput, String>>(
            CredentialInput.OAuthClientCredentialsInput(
                "id",
                "secret",
                "https://x/token",
                OAuthAuthStyle.BASIC,
                "s"
            ) to "oauth_client_credentials",
            CredentialInput.AppleDeveloperKeyInput("team", "key", "pem") to "apple_developer_key",
            CredentialInput.ApiKeyInput("k") to "api_key",
            CredentialInput.ApiKeyPairInput("k", "s") to "api_key_pair",
            CredentialInput.TidalSessionInput(TidalSessionFormat.TDN, "id", "secret", "{}") to "tidal_device_session",
            CredentialInput.FileInput(listOf(file)) to "file",
        )
        for ((value, tag) in cases) {
            val encoded = json.encodeToString<CredentialInput>(value)
            assertEquals(tag, discriminator(encoded))
            assertEquals(value, json.decodeFromString<CredentialInput>(encoded))
        }
    }

    @Test
    fun defaultsAreEncodedAndNamesValidate() {
        val encoded = json.encodeToString(TokenResponse("t", expiresAt = 5L, grants = emptyList()))
        assertEquals("Bearer", Json.parseToJsonElement(encoded).jsonObject.getValue("tokenType").jsonPrimitive.content)
        assertEquals(true, CredentialNames.isValid(CredentialNames.plugin("p", "n")))
        assertEquals(false, CredentialNames.isValid("Bad Name"))
    }
}
