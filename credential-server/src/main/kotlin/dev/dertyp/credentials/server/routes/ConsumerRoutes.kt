package dev.dertyp.credentials.server.routes

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.CredentialServerDeps
import dev.dertyp.credentials.server.auth.CONSUMER_AUTH
import dev.dertyp.credentials.server.auth.consumer
import dev.dertyp.credentials.server.broker.CredentialException
import dev.dertyp.credentials.server.crypto.SecretHasher
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

private val serverVersion: String =
    CredentialServerDeps::class.java.`package`?.implementationVersion ?: "dev"

fun Route.publicRoutes(deps: CredentialServerDeps) {
    get(CredentialProtocol.HEALTH_PATH) {
        call.respond(
            CredentialServerHealth(
                ok = true,
                protocolVersion = CredentialProtocol.PROTOCOL_VERSION,
                version = serverVersion
            )
        )
    }

    post(CredentialProtocol.TOKEN_PATH) {
        val request = call.receive<TokenRequest>()
        val client = deps.store.clientRecordByClientId(request.clientId)
        val matches = SecretHasher.matches(request.clientSecret, client?.secretHash)
        if (client == null || !matches || !client.enabled) {
            throw CredentialException(CredentialErrorCode.UNAUTHORIZED, "Invalid client credentials")
        }
        val grants = deps.store.grantsOf(client.id)
        val issued = deps.tokenIssuer.issue(client, grants)
        deps.store.markTokenIssued(client.id)
        call.respond(TokenResponse(accessToken = issued.token, expiresAt = issued.expiresAt, grants = grants))
    }
}

fun Route.consumerRoutes(deps: CredentialServerDeps) {
    authenticate(CONSUMER_AUTH) {
        get(CredentialProtocol.CREDENTIALS_PATH) {
            call.respond(deps.store.grantsOf(call.consumer().clientUuid))
        }

        get("${CredentialProtocol.CREDENTIALS_PATH}/{name}") {
            val principal = call.consumer()
            val name = call.credentialName()
            if (name !in principal.grants) {
                throw CredentialException(
                    CredentialErrorCode.NOT_GRANTED,
                    "Credential $name is not granted to this client"
                )
            }
            val resolved: ResolvedCredential = deps.resolver.resolve(name)
            deps.store.touchGrant(principal.clientUuid, name)
            call.respond(resolved)
        }

        put("${CredentialProtocol.CREDENTIALS_PATH}/{name}/files") {
            val principal = call.consumer()
            val name = call.credentialName()
            if (name !in principal.writeBack) {
                throw CredentialException(
                    CredentialErrorCode.NOT_GRANTED,
                    "Write-back of $name is not granted to this client"
                )
            }
            val request = call.receive<WriteBackRequest>()
            val result = deps.resolver.writeBack(name, request)
            deps.store.touchGrant(principal.clientUuid, name)
            call.respond(result)
        }
    }
}

internal fun ApplicationCall.credentialName(): String =
    parameters["name"] ?: throw CredentialException(CredentialErrorCode.INVALID, "Credential name missing")
