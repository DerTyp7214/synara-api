package dev.dertyp.proxy

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    EngineMain.main(args)
}

private val logger = LoggerFactory.getLogger("Proxy")

class ServerTunnel(
    val id: String,
    val name: String?,
    val session: WebSocketServerSession,
    val http: Boolean
) {
    val clients = ConcurrentHashMap<UUID, WebSocketServerSession>()
    val sendChannel = Channel<ProxyMessage>(Channel.UNLIMITED)
    val httpStreams = HttpStreams(sendChannel)

    init {
        session.launch {
            for (msg in sendChannel) {
                val frame = try {
                    msg.toFrame()
                } catch (e: IllegalArgumentException) {
                    logger.error("[{}] Dropping a message that cannot be encoded", id, e)
                    continue
                }
                try {
                    session.send(frame)
                } catch (e: Exception) {
                    logger.error("[{}] Failed to send message to server tunnel", id, e)
                    break
                }
            }
        }
    }
}

fun Application.module() {
    val tunnels = ConcurrentHashMap<String, ServerTunnel>()

    install(CallLogging) {
        level = Level.INFO
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(ContentNegotiation) {
        json()
    }

    routing {
        val proxyKey = environment.config.propertyOrNull("proxy.key")?.getString()

        get("/instances") {
            val instances = tunnels.values.map { InstanceInfo(it.id, it.name) }
            call.respond(instances)
        }

        webSocket("/proxy/server") {
            val key = call.request.queryParameters["key"] ?: call.request.headers["X-Proxy-Key"]
            if (!proxyKey.isNullOrEmpty() && key != proxyKey) {
                logger.warn("Server connection rejected: Invalid or missing API key")
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid or missing API key"))
                return@webSocket
            }

            val requestedId = call.request.queryParameters["id"]
            val serverName = call.request.queryParameters["name"]
            val http = call.request.queryParameters[HttpTunnel.CAPABILITY_PARAMETER] == HttpTunnel.CAPABILITY_VALUE

            if (requestedId in reservedInstanceIds) {
                logger.warn("Server connection rejected: ID {} is reserved", requestedId)
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Requested ID is reserved"))
                return@webSocket
            }

            var assignedId = requestedId ?: UUID.randomUUID().toString().take(8)

            if (tunnels.containsKey(assignedId)) {
                if (requestedId != null) {
                    logger.warn("Server connection rejected: ID {} already in use", assignedId)
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Requested ID already in use"))
                    return@webSocket
                } else {
                    assignedId = UUID.randomUUID().toString().take(8)
                }
            }

            logger.info("Server connected: ID={}, Name={}, HTTP={}", assignedId, serverName, http)
            val tunnel = ServerTunnel(assignedId, serverName, this, http)
            tunnels[assignedId] = tunnel

            send(ProxyMessage.AssignedId(assignedId).toFrame())

            try {
                for (frame in incoming) {
                    val msg = ProxyMessage.fromFrame(frame) ?: continue
                    when (msg) {
                        is ProxyMessage.ClientFrame -> {
                            val client = tunnel.clients[msg.clientId]
                            if (client != null) {
                                val outFrame =
                                    if (msg.isBinary) Frame.Binary(true, msg.data) else Frame.Text(String(msg.data))
                                try {
                                    client.send(outFrame)
                                } catch (e: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    logger.info("[{}] Client {} is gone: {}", assignedId, msg.clientId, e.toString())
                                    tunnel.clients.remove(msg.clientId)
                                    tunnel.sendChannel.send(ProxyMessage.ClientDisconnected(msg.clientId))
                                    client.close()
                                }
                            } else {
                                logger.debug("[{}] Dropping frame for unknown client {}", assignedId, msg.clientId)
                            }
                        }

                        is ProxyMessage.ClientDisconnected -> {
                            logger.info("[{}] Client {} disconnected by server", assignedId, msg.clientId)
                            tunnel.clients.remove(msg.clientId)?.close()
                        }

                        is ProxyMessage.Ping -> {
                            tunnel.sendChannel.send(ProxyMessage.Pong)
                        }

                        is ProxyMessage.HttpResponse,
                        is ProxyMessage.HttpBody,
                        is ProxyMessage.HttpWindow,
                        is ProxyMessage.HttpReset -> tunnel.httpStreams.dispatch(msg)

                        else -> {}
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logger.error("[{}] Error in server tunnel", assignedId, e)
                }
            } finally {
                tunnels.remove(assignedId)
                tunnel.httpStreams.close()
                tunnel.clients.values.forEach {
                    it.close(CloseReason(CloseReason.Codes.SERVICE_RESTART, "Server disconnected"))
                }
                logger.info("Server disconnected: ID={}", assignedId)
            }
        }

        webSocket("/{id}/{...}") {
            val id = call.parameters["id"] ?: return@webSocket
            val uri = call.request.uri.removePrefix("/$id")

            if (id in reservedInstanceIds) return@webSocket

            if (!uri.startsWith("/rpc")) {
                logger.warn("Rejecting non-RPC path request for ID {}: {}", id, uri)
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Only RPC paths are proxied"))
                return@webSocket
            }

            val tunnel = tunnels[id] ?: run {
                logger.warn("Rejecting client request: No server connected with ID {}", id)
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "No server connected with ID $id"))
                return@webSocket
            }

            val clientId = UUID.randomUUID()
            logger.info("[{}] New client connection {} for URI {}", id, clientId, uri)
            tunnel.clients[clientId] = this

            val headers = call.request.headers.entries().associate { it.key to it.value.joinToString(",") }
            tunnel.sendChannel.send(ProxyMessage.NewClient(clientId, uri, headers))

            try {
                for (frame in incoming) {
                    val isBinary = frame is Frame.Binary
                    tunnel.sendChannel.send(ProxyMessage.ClientFrame(clientId, frame.data, isBinary))
                }
            } catch (e: Exception) {
                logger.error("[{}] Error in client connection {}", id, clientId, e)
            } finally {
                logger.info("[{}] Client {} disconnected", id, clientId)
                tunnel.clients.remove(clientId)
                tunnel.sendChannel.send(ProxyMessage.ClientDisconnected(clientId))
            }
        }

        httpTunnel(tunnels)
    }
}
