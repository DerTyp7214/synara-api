package dev.dertyp

import dev.dertyp.routing.*
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.HandshakeService
import dev.dertyp.services.JwtService
import dev.hayden.KHealth
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.koin
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class, ExperimentalAtomicApi::class)
fun Application.configureRouting() {
    install(ContentNegotiation) {
        json(AppJson)
        protobuf()
    }
    install(SSE)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 5.minutes
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(Krpc) {
        serialization {
            cbor(AppCbor)
            //synaraCbor(AppCbor)
        }
    }
    install(KHealth) {
        successfulCheckStatusCode = HttpStatusCode.Accepted
        unsuccessfulCheckStatusCode = HttpStatusCode.Accepted
        healthChecks {
            check("available") {
                true
            }
        }

        readyChecks {
            check("indexer_ready") {
                val indexer by inject<Indexer>()
                !indexer.isActive.load()
            }
            check("transcoder_ready") {
                !AudioUtils.isTranscoderActive.load()
            }
        }
    }
    install(OpenApi) {
        schemas {
            generator = SchemaGenerator.kotlinx(AppJson) {
                overwrite(SchemaGenerator.TypeOverwrites.JavaUuid())
                overwrite(SchemaGenerator.TypeOverwrites.KotlinUuid())
            }
        }
        security {
            securityScheme("UserAuth") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "JWT"
            }
        }
    }
    install(Compression)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        staticResources("/static", "static")

        route("api.json") {
            openApi()
        }
        route("swagger") {
            swaggerUI("/api.json") {

            }
        }

        val koin = koin()
        val jwtService by inject<JwtService>()

        rpc("/rpc") {
            //withSynaraPack()
            registerPublicServices(koin)
        }

        rpc("/rpc/auth") {
            //withSynaraPack()
            registerPublicServices(koin)
        }

        registerPublicRestServices(koin)

        webSocket("/handshake") {
            val response = HandshakeService.determineHandshakeResponse(call)
            send(Frame.Binary(true, AppCbor.encodeToByteArray(response)))
            close()
        }

        jwtService.authenticated(this) {
            rpc("/rpc/services") {
                //withSynaraPack()
                registerAuthenticatedServices(koin)
            }

            registerAuthenticatedRestServices(koin)
        }

        jwtService.authenticate(this)

        radioRouting()

        mirrorRouting()
    }
}
