package dev.dertyp.credentials.server

import dev.dertyp.credentials.CredentialError
import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialJson
import dev.dertyp.credentials.server.auth.adminKey
import dev.dertyp.credentials.server.auth.consumerJwt
import dev.dertyp.credentials.server.broker.CredentialException
import dev.dertyp.credentials.server.cli.CredentialCli
import dev.dertyp.credentials.server.routes.adminRoutes
import dev.dertyp.credentials.server.routes.consumerRoutes
import dev.dertyp.credentials.server.routes.publicRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("CredentialServer")

fun main(args: Array<String>) {
    if (CredentialCli.isCommand(args)) {
        exitProcess(CredentialCli.main(args))
    }
    EngineMain.main(args)
}

fun Application.module() {
    val deps = CredentialServerDeps.create(environment.config)
    monitor.subscribe(ApplicationStopped) { deps.close() }
    credentialServerModule(deps)
}

fun Application.credentialServerModule(deps: CredentialServerDeps) {
    if (deps.settings.adminKey.isBlank()) {
        logger.warn("CREDENTIAL_SERVER_ADMIN_KEY is not set. The admin API answers 503 until it is configured")
    }
    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json(CredentialJson.json)
    }
    install(StatusPages) {
        exception<CredentialException> { call, cause ->
            call.respond(cause.code.httpStatus(), CredentialError(cause.code, cause.message ?: cause.code.name))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                CredentialError(CredentialErrorCode.INVALID, cause.cause?.message ?: cause.message ?: "Bad request"),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, CredentialError(CredentialErrorCode.INVALID, cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            logger.error("Unhandled error on {}", call.request.local.uri, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                CredentialError(CredentialErrorCode.UPSTREAM_FAILED, "Internal error"),
            )
        }
    }
    install(Authentication) {
        consumerJwt(deps.tokenIssuer, deps.store)
        adminKey(deps.settings.adminKey)
    }
    routing {
        publicRoutes(deps)
        consumerRoutes(deps)
        adminRoutes(deps)
    }
}

fun CredentialErrorCode.httpStatus(): HttpStatusCode = when (this) {
    CredentialErrorCode.UNAUTHORIZED -> HttpStatusCode.Unauthorized
    CredentialErrorCode.NOT_GRANTED -> HttpStatusCode.Forbidden
    CredentialErrorCode.NOT_FOUND -> HttpStatusCode.NotFound
    CredentialErrorCode.UPSTREAM_FAILED -> HttpStatusCode.BadGateway
    CredentialErrorCode.NEEDS_LOGIN -> HttpStatusCode.FailedDependency
    CredentialErrorCode.CONFLICT -> HttpStatusCode.Conflict
    CredentialErrorCode.INVALID -> HttpStatusCode.BadRequest
}
