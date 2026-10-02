package dev.dertyp.credentials.server.auth

import dev.dertyp.credentials.CredentialError
import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialProtocol
import dev.dertyp.credentials.server.crypto.SecretHasher
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.response.respond

const val ADMIN_AUTH = "admin"

data object AdminPrincipal

class AdminKeyAuthenticationProvider(config: Config) : AuthenticationProvider(config) {
    private val adminKey = config.adminKey

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        var adminKey: String = ""
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        if (adminKey.isBlank()) {
            context.challenge(ADMIN_AUTH, AuthenticationFailedCause.Error("Admin key is not configured")) { challenge, call ->
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    CredentialError(CredentialErrorCode.UNAUTHORIZED, "Admin API is disabled because CREDENTIAL_SERVER_ADMIN_KEY is not set"),
                )
                challenge.complete()
            }
            return
        }
        val provided = context.call.request.headers[CredentialProtocol.ADMIN_KEY_HEADER]
        if (provided != null && SecretHasher.constantTimeEquals(provided, adminKey)) {
            context.principal(name, AdminPrincipal)
            return
        }
        val cause = if (provided == null) AuthenticationFailedCause.NoCredentials else AuthenticationFailedCause.InvalidCredentials
        context.challenge(ADMIN_AUTH, cause) { challenge, call ->
            call.respond(
                HttpStatusCode.Unauthorized,
                CredentialError(CredentialErrorCode.UNAUTHORIZED, "Missing or invalid ${CredentialProtocol.ADMIN_KEY_HEADER}"),
            )
            challenge.complete()
        }
    }
}

fun AuthenticationConfig.adminKey(adminKey: String) {
    register(AdminKeyAuthenticationProvider(AdminKeyAuthenticationProvider.Config(ADMIN_AUTH).apply { this.adminKey = adminKey }))
}
