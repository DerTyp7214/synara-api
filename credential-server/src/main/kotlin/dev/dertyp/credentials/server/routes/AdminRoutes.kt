package dev.dertyp.credentials.server.routes

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.CredentialServerDeps
import dev.dertyp.credentials.server.auth.ADMIN_AUTH
import dev.dertyp.credentials.server.broker.CredentialException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.Serializable

val NDJSON: ContentType = ContentType("application", "x-ndjson")

@Serializable
data class SigningKeyRotation(val kid: String)

fun Route.adminRoutes(deps: CredentialServerDeps) {
    route(CredentialProtocol.ADMIN_PREFIX) {
        authenticate(ADMIN_AUTH) {
            clientRoutes(deps)
            credentialRoutes(deps)
            tidalLoginRoutes(deps)

            post("/signing-keys/rotate") {
                call.respond(SigningKeyRotation(deps.tokenIssuer.rotate()))
            }
        }
    }
}

private fun Route.clientRoutes(deps: CredentialServerDeps) {
    route("/clients") {
        get {
            call.respond(deps.store.listClients())
        }

        post {
            val request = call.receive<CreateClientRequest>()
            call.respond(HttpStatusCode.Created, deps.store.createClient(request.name, request.grants))
        }

        route("/{id}") {
            get {
                call.respond(deps.store.client(call.clientRef()) ?: clientNotFound(call.clientRef()))
            }

            patch {
                val request = call.receive<UpdateClientRequest>()
                call.respond(deps.store.updateClient(call.clientRef(), request))
            }

            delete {
                if (!deps.store.deleteClient(call.clientRef())) clientNotFound(call.clientRef())
                call.respond(HttpStatusCode.NoContent)
            }

            post("/rotate-secret") {
                call.respond(deps.store.rotateSecret(call.clientRef()))
            }

            post("/revoke-tokens") {
                call.respond(deps.store.revokeTokens(call.clientRef()))
            }

            put("/grants") {
                val request = call.receive<SetGrantsRequest>()
                call.respond(deps.store.setGrants(call.clientRef(), request.grants))
            }
        }
    }
}

private fun Route.credentialRoutes(deps: CredentialServerDeps) {
    get("/presets") {
        call.respond(deps.resolver.presets())
    }

    route(CredentialProtocol.CREDENTIALS_PATH) {
        get {
            call.respond(deps.store.listCredentials())
        }

        route("/{name}") {
            get {
                val name = call.credentialName()
                call.respond(deps.store.credential(name) ?: credentialNotFound(name))
            }

            put {
                val name = call.credentialName()
                val request = call.receive<UpsertCredentialRequest>()
                call.respond(deps.admin.upsert(name, request))
            }

            delete {
                val name = call.credentialName()
                if (!deps.admin.delete(name)) credentialNotFound(name)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/test") {
                val name = call.credentialName()
                if (deps.store.kind(name) == null) credentialNotFound(name)
                call.respond(deps.resolver.test(name))
            }

            post("/tidal-login") {
                val name = call.credentialName()
                val request = call.receive<TidalLoginStart>()
                call.respond(deps.admin.startTidalLogin(name, request))
            }
        }
    }
}

private fun Route.tidalLoginRoutes(deps: CredentialServerDeps) {
    route("/tidal-logins/{id}") {
        get {
            val id = call.loginId()
            val events = deps.tidalLogins.events(id) ?: loginNotFound(id)
            call.respond(events.value)
        }

        get("/events") {
            val id = call.loginId()
            val events = deps.tidalLogins.events(id) ?: loginNotFound(id)
            call.respondTextWriter(NDJSON) {
                events
                    .transformWhile { event ->
                        emit(event)
                        event.state == TidalLoginState.PENDING
                    }
                    .collect { event ->
                        write(CredentialJson.json.encodeToString(TidalLoginEvent.serializer(), event))
                        write("\n")
                        flush()
                    }
            }
        }

        delete {
            deps.tidalLogins.cancel(call.loginId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.clientRef(): String =
    parameters["id"] ?: throw CredentialException(CredentialErrorCode.INVALID, "Client id missing")

private fun ApplicationCall.loginId(): String =
    parameters["id"] ?: throw CredentialException(CredentialErrorCode.INVALID, "Login id missing")

private fun clientNotFound(ref: String): Nothing =
    throw CredentialException(CredentialErrorCode.NOT_FOUND, "Client $ref does not exist")

private fun credentialNotFound(name: String): Nothing =
    throw CredentialException(CredentialErrorCode.NOT_FOUND, "Credential $name does not exist")

private fun loginNotFound(id: String): Nothing =
    throw CredentialException(CredentialErrorCode.NOT_FOUND, "Tidal login $id does not exist")
