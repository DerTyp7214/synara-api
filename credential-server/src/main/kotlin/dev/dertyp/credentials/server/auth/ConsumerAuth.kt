package dev.dertyp.credentials.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.JWTVerifier
import dev.dertyp.credentials.CredentialError
import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.server.crypto.TokenIssuer
import dev.dertyp.credentials.server.db.CredentialStore
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.UUID

const val CONSUMER_AUTH = "consumer"

data class ConsumerPrincipal(
    val clientUuid: UUID,
    val grants: Set<String>,
    val writeBack: Set<String>,
)

fun AuthenticationConfig.consumerJwt(issuer: TokenIssuer, store: CredentialStore) {
    val verifierLookup: (HttpAuthHeader) -> JWTVerifier? = { header ->
        val token = (header as? HttpAuthHeader.Single)?.blob
        val kid = token?.let { runCatching { JWT.decode(it).keyId }.getOrNull() }
        kid?.let { issuer.verifierFor(it) }
    }
    jwt(CONSUMER_AUTH) {
        realm = "synara-credentials"
        verifier(verifierLookup)
        validate { credential ->
            val clientUuid = credential.payload.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@validate null
            val version = credential.payload.getClaim(TokenIssuer.CLAIM_VERSION).asInt() ?: return@validate null
            val client = store.clientRecord(clientUuid) ?: return@validate null
            if (!client.enabled || client.tokenVersion != version) return@validate null
            ConsumerPrincipal(
                clientUuid = clientUuid,
                grants = credential.payload.getClaim(TokenIssuer.CLAIM_GRANTS).asList(String::class.java).orEmpty()
                    .toSet(),
                writeBack = credential.payload.getClaim(TokenIssuer.CLAIM_WRITE_BACK).asList(String::class.java)
                    .orEmpty().toSet(),
            )
        }
        challenge { _, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                CredentialError(CredentialErrorCode.UNAUTHORIZED, "Token is missing, invalid, expired or revoked"),
            )
        }
    }
}

fun ApplicationCall.consumer(): ConsumerPrincipal = principal<ConsumerPrincipal>()
    ?: error("Consumer principal missing")
