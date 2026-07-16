package dev.dertyp.services.subsonic

import dev.dertyp.data.User
import dev.dertyp.services.ApiKeyService
import io.ktor.http.*
import java.security.MessageDigest

sealed class SubsonicAuthResult {
    data class Ok(val user: User) : SubsonicAuthResult()
    data class Failure(val code: Int, val message: String) : SubsonicAuthResult()
}

class SubsonicAuthenticator(
    private val apiKeyService: ApiKeyService,
    private val credentialService: SubsonicCredentialService,
) {
    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    suspend fun authenticate(params: Parameters): SubsonicAuthResult {
        val apiKey = params["apiKey"]
        val username = params["u"]
        val password = params["p"]
        val token = params["t"]
        val salt = params["s"]

        if (apiKey != null) {
            if (username != null || password != null || token != null || salt != null) {
                return SubsonicAuthResult.Failure(43, "Multiple conflicting authentication mechanisms provided")
            }
            val user = apiKeyService.resolveUser(apiKey, SubsonicPlugin.SCOPE)
                ?: return SubsonicAuthResult.Failure(44, "Invalid API key")
            return SubsonicAuthResult.Ok(user)
        }

        if (username == null) {
            return SubsonicAuthResult.Failure(42, "No supported authentication mechanism provided")
        }

        if (token != null && salt != null) {
            val (user, secret) = credentialService.secretForUsername(username)
                ?: return SubsonicAuthResult.Failure(40, "Wrong username or password")
            if (!constantTimeEquals(md5Hex(secret + salt), token.lowercase())) {
                return SubsonicAuthResult.Failure(40, "Wrong username or password")
            }
            return SubsonicAuthResult.Ok(user)
        }

        if (password != null) {
            val decoded = if (password.startsWith("enc:")) {
                password.removePrefix("enc:")
                    .chunked(2)
                    .mapNotNull { it.toIntOrNull(16)?.toChar() }
                    .joinToString("")
            } else password
            val (user, secret) = credentialService.secretForUsername(username)
                ?: return SubsonicAuthResult.Failure(40, "Wrong username or password")
            if (!constantTimeEquals(secret, decoded)) {
                return SubsonicAuthResult.Failure(40, "Wrong username or password")
            }
            return SubsonicAuthResult.Ok(user)
        }

        return SubsonicAuthResult.Failure(42, "No supported authentication mechanism provided")
    }
}
