package dev.dertyp.services

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.ProxiedKey
import dev.dertyp.proxy.ProxyMessage
import dev.dertyp.routing.registerAuthenticatedServices
import dev.dertyp.routing.registerPublicServices
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.RequestCookies
import io.ktor.server.response.ApplicationResponse
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.rpc.krpc.KrpcTransport
import kotlinx.rpc.krpc.KrpcTransportMessage
import kotlinx.rpc.krpc.server.KrpcServer
import org.koin.core.component.get
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class ReverseProxyService(
    config: ApplicationConfig
) : Service() {
    private val client = HttpClient {
        install(WebSockets)
    }

    private val _isConnected = AtomicBoolean(false)
    val isConnected: Boolean get() = _isConnected.load()

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.load()

    var lastInteraction: TimeMark? = null
        private set

    val isConfigured: Boolean get() = !proxyHost.isNullOrBlank() && controlPort != null

    @Volatile
    private var connectionJob: Job? = null

    val proxyHost = config.propertyOrNull("proxy.hostname")?.getString()
    val controlPort = config.propertyOrNull("proxy.controlPort")?.getString()?.toInt()
    val proxySsl = config.propertyOrNull("proxy.ssl")?.getString()?.toBoolean() ?: false
    private val requestedId = config.propertyOrNull("proxy.id")?.getString() ?: UUID.randomUUID().toString().take(8)
    private val serverName = config.propertyOrNull("proxy.name")?.getString()
    private val proxyKey = config.propertyOrNull("proxy.key")?.getString()
    
    var proxyId: String? = null
        private set

    override suspend fun startService() {
        if (!isConfigured) {
            logger.info("Reverse proxy not fully configured (host=$proxyHost, controlPort=$controlPort, id=$requestedId, name=$serverName)")
            return
        }

        _isRunning.store(true)
        try {
            coroutineScope {
                while (isActive) {
                    val job = launch {
                        try {
                            connectToProxy()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logger.error("Error in reverse proxy connection, retrying in 5s: ${e.message}")
                            delay(5.seconds)
                        } finally {
                            proxyId = null
                            _isConnected.store(false)
                        }
                    }
                    connectionJob = job
                    job.join()
                }
            }
        } finally {
            _isRunning.store(false)
            logger.info("Reverse proxy service stopped")
        }
    }

    fun restartService() {
        logger.info("Restarting reverse proxy service (cancelling current connection job)")
        connectionJob?.cancel()
    }

    private suspend fun connectToProxy() {
        logger.info("Attempting to connect to reverse proxy at $proxyHost:$controlPort (SSL=$proxySsl)")

        client.webSocket(
            request = {
                url {
                    protocol = if (proxySsl) URLProtocol.WSS else URLProtocol.WS
                    host = proxyHost!!
                    port = controlPort!!
                    path("/proxy/server")
                    
                    parameters.append("id", requestedId)
                    serverName?.let { parameters.append("name", it) }
                    proxyKey?.let { parameters.append("key", it) }
                }
            }
        ) {
            val activeServers = ConcurrentHashMap<UUID, ProxyKrpcServer>()
            logger.info("Connected to reverse proxy control at $proxyHost:$controlPort")
            _isConnected.store(true)
            lastInteraction = TimeSource.Monotonic.markNow()

            try {
                launch {
                    while (isActive) {
                        delay(10.seconds)
                        val last = lastInteraction
                        if (last != null && last.elapsedNow() > 30.seconds) {
                            logger.warn("Reverse proxy health check failed (no interaction for 30s), reconnecting...")
                            this@webSocket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Health check failed"))
                            break
                        }
                        send(ProxyMessage.Ping.toFrame())
                    }
                }

                for (frame in incoming) {
                    lastInteraction = TimeSource.Monotonic.markNow()
                    val msg = ProxyMessage.fromFrame(frame) ?: continue
                    val clientId = msg.clientId

                    when (msg) {
                        is ProxyMessage.NewClient -> {
                            val transport = MultiplexedTransport(this.coroutineContext, clientId, this)
                            val server = ProxyKrpcServer(transport)
                            activeServers[clientId] = server
                            
                            launch {
                                try {
                                    setupServer(server, msg)
                                    server.awaitCompletion()
                                } catch (e: Exception) {
                                    logger.error("Error in proxied server for $clientId", e)
                                } finally {
                                    activeServers.remove(clientId)
                                }
                            }
                        }
                        is ProxyMessage.ClientFrame -> {
                            activeServers[clientId]?.transport?.onData(msg.data, msg.isBinary)
                        }
                        is ProxyMessage.ClientDisconnected -> {
                            logger.info("Proxied client disconnected: $clientId")
                            activeServers.remove(clientId)?.close()
                        }
                        is ProxyMessage.AssignedId -> {
                            this@ReverseProxyService.proxyId = msg.id
                            logger.info("Proxy assigned ID: ${msg.id}")
                        }
                        is ProxyMessage.Pong -> {}
                        is ProxyMessage.Ping -> {
                            send(ProxyMessage.Pong.toFrame())
                        }
                    }
                }
            } finally {
                _isConnected.store(false)
                activeServers.values.forEach { it.close() }
                logger.info("Disconnected from reverse proxy control")
            }
        }
    }

    private suspend fun setupServer(server: ProxyKrpcServer, metadata: ProxyMessage.NewClient) {
        val jwtService = get<JwtService>()
        val authHeader = metadata.headers["Authorization"]
        val token = authHeader?.removePrefix("Bearer ")
        val principal = token?.let { jwtService.validateToken(it) }
        
        val call = ProxyCall(application = get(), uriString = metadata.uri, headersMap = metadata.headers, principal = principal)
        
        val path = metadata.uri.substringBefore('?')
        if (path == "/rpc" || path == "/rpc/auth") {
            server.registerPublicServices(getKoin(), call)
        } else if (path == "/rpc/services") {
            val user = principal?.let { 
                get<UserService>().findUserByUsername(it.payload.getClaim("usr").asString())
            }
            if (user != null) {
                server.registerAuthenticatedServices(getKoin(), call, user)
            } else {
                server.close("Unauthorized")
            }
        } else {
            server.close("Invalid path")
        }
    }

    private class ProxyKrpcServer(
        val transport: MultiplexedTransport
    ) : KrpcServer(ApplicationScope.rpcConfig, transport)

    private class MultiplexedTransport(
        override val coroutineContext: CoroutineContext,
        val clientId: UUID,
        val proxySession: DefaultClientWebSocketSession
    ) : KrpcTransport {
        private val incoming = Channel<KrpcTransportMessage>(Channel.UNLIMITED)

        override suspend fun send(message: KrpcTransportMessage) {
            when (message) {
                is KrpcTransportMessage.BinaryMessage -> {
                    proxySession.send(ProxyMessage.ClientFrame(clientId, message.value, true).toFrame())
                }
                is KrpcTransportMessage.StringMessage -> {
                    proxySession.send(ProxyMessage.ClientFrame(clientId, message.value.toByteArray(), false).toFrame())
                }
            }
        }

        override suspend fun receive(): KrpcTransportMessage = incoming.receive()

        suspend fun onData(data: ByteArray, isBinary: Boolean) {
            if (isBinary) {
                incoming.send(KrpcTransportMessage.BinaryMessage(data))
            } else {
                incoming.send(KrpcTransportMessage.StringMessage(String(data)))
            }
        }
    }

    private class ProxyCall(
        override val application: Application,
        val uriString: String,
        val headersMap: Map<String, String>,
        val principal: JWTPrincipal?
    ) : ApplicationCall {
        override val attributes = Attributes(true).apply {
            put(ProxiedKey, true)
            principal?.let {
                val context = AuthenticationContext(this@ProxyCall)
                context.principal(it)
                put(AttributeKey<AuthenticationContext>("AuthContext"), context)
            }
        }
        override val parameters: Parameters by lazy {
            val query = uriString.substringAfter('?', "")
            parseQueryString(query)
        }
        override val coroutineContext: CoroutineContext = application.coroutineContext
        
        override val request = object : ApplicationRequest {
            override val call: ApplicationCall get() = this@ProxyCall
            override val cookies: RequestCookies get() = throw UnsupportedOperationException()
            override val headers: Headers = Headers.build {
                headersMap.forEach { (k, v) -> append(k, v) }
            }
            override val local: RequestConnectionPoint = object : RequestConnectionPoint {
                @Deprecated("Use localHost or serverHost instead", level = DeprecationLevel.ERROR)
                @Suppress("DEPRECATION")
                override val host: String = "127.0.0.1"
                override val method: HttpMethod = HttpMethod.Get
                @Deprecated("Use localPort or serverPort instead", level = DeprecationLevel.ERROR)
                @Suppress("DEPRECATION")
                override val port: Int = 80
                override val remoteHost: String = "127.0.0.1"
                override val remoteAddress: String = "127.0.0.1"
                override val remotePort: Int = 0
                override val scheme: String = "http"
                override val uri: String = uriString
                override val version: String = "HTTP/1.1"
                override val serverHost: String = "127.0.0.1"
                override val serverPort: Int = 80
                override val localHost: String = "127.0.0.1"
                override val localAddress: String = "127.0.0.1"
                override val localPort: Int = 80
            }
            override val queryParameters: Parameters get() = this@ProxyCall.parameters
            override val rawQueryParameters: Parameters get() = this@ProxyCall.parameters
            override fun receiveChannel(): ByteReadChannel = throw UnsupportedOperationException()
        }
        override val response: ApplicationResponse get() = throw UnsupportedOperationException()
        
        override suspend fun respond(message: Any?, typeInfo: TypeInfo?) {
            throw UnsupportedOperationException()
        }

        override suspend fun <T> receiveNullable(typeInfo: TypeInfo): T? {
            throw UnsupportedOperationException()
        }
    }
}
