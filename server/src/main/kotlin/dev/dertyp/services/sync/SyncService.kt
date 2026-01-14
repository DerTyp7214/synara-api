package dev.dertyp.services.sync

import com.github.benmanes.caffeine.cache.Caffeine
import dev.dertyp.core.getUsername
import dev.dertyp.data.User
import dev.dertyp.db.SyncServiceTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ISyncService
import dev.dertyp.services.Service
import dev.dertyp.services.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.html.*
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.ktor.ext.get
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
abstract class SyncService(
    protected val environment: ApplicationEnvironment,
    protected val user: User
) : ISyncService, Service() {
    abstract val clientIdConfigPath: String
    abstract val clientSecretConfigPath: String
    abstract val scopes: List<String>

    private val serviceType: ISyncService.SyncServiceType = when {
        this is TidalSyncService -> ISyncService.SyncServiceType.tidal
        else -> ISyncService.SyncServiceType.unknown
    }
    private val redirectPath: String by lazy { "/sync/${serviceType.name}/callback" }

    protected val clientId by lazy { environment.config.propertyOrNull(clientIdConfigPath)?.getString() }
    protected val clientSecret by lazy { environment.config.propertyOrNull(clientSecretConfigPath)?.getString() }

    companion object {
        val authFlowCache = Caffeine.newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build<String, String>()

        private suspend fun getInstance(
            name: String?,
            call: ApplicationCall,
            username: String? = null
        ): SyncService? {
            return when (name) {
                ISyncService.SyncServiceType.tidal.name -> {
                    val environment = call.application.environment
                    val user = call.get<UserService>()
                        .findUserByUsername(username ?: call.getUsername())
                    if (user == null) return null
                    TidalSyncService(environment, user)
                }

                else -> null
            }
        }

        suspend fun getInstance(call: ApplicationCall, username: String? = null): SyncService {
            val instance = getInstance(call.parameters["service"], call, username)
                ?: throw IllegalStateException("Service not found")

            return instance
        }

        fun getInstance(user: User, environment: ApplicationEnvironment, type: ISyncService.SyncServiceType): SyncService {
            return when (type) {
                ISyncService.SyncServiceType.tidal -> TidalSyncService(environment, user)
                else -> throw IllegalArgumentException("Invalid sync service type: $type")
            }
        }

        suspend fun handleAuth(call: ApplicationCall) {
            val service = getInstance(call.parameters["service"], call)
                ?: return call.respond(HttpStatusCode.BadRequest, "Invalid Service")

            service.handleAuth(call)
        }

        suspend fun handleCallback(call: ApplicationCall, username: String?) {
            val service = getInstance(call.parameters["service"], call, username) ?: return call.respond(
                HttpStatusCode.BadRequest,
                "Invalid Service"
            )

            service.handleCallback(call)
        }
    }

    protected suspend fun setToken(token: ISyncService.Token) {
        if (token.createdAt != null) {
            dbQuery {
                SyncServiceTable.upsert {
                    it[this.name] = serviceType.name
                    it[this.ownerId] = user.id
                    it[this.scope] = token.scope ?: ""
                    it[this.accessToken] = token.accessToken
                    it[this.refreshToken] = token.refreshToken ?: ""
                    it[this.expiresIn] = token.expiresIn
                    it[this.tokenType] = token.tokenType
                    it[this.userId] = token.userId
                    it[this.createdAt] = token.createdAt!!
                }
            }
        }
    }

    protected suspend fun getToken(): ISyncService.Token? = dbQuery {
        SyncServiceTable
            .select(SyncServiceTable.columns)
            .where { SyncServiceTable.ownerId eq user.id }
            .andWhere { SyncServiceTable.name eq serviceType.name }
            .map(::mapTableToToken)
    }.singleOrNull()

    suspend fun getAccessToken(): ISyncService.Token? {
        val token = getToken() ?: return null
        val currentTimeMillis = System.currentTimeMillis()
        val bufferMillis = TimeUnit.MINUTES.toMillis(5)

        if (currentTimeMillis < ((token.createdAt ?: 0) + token.expiresIn.seconds.inWholeMilliseconds - bufferMillis))
            return token

        val newToken = refreshToken(token)

        setToken(newToken)

        return newToken
    }

    fun generateCodeVerifier(length: Int = 128): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9') + '-' + '.' + '_' + '~'
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes)
        val digest = messageDigest.digest()

        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(digest)
    }

    fun generateRedirectUrl(call: ApplicationCall): String {
        return url {
            if (URLProtocol.byName[call.request.local.scheme] != null)
                protocol = URLProtocol.byName[call.request.local.scheme]!!
            host = call.request.local.serverHost
            port = call.request.local.serverPort
            encodedPath = redirectPath
        }
    }

    suspend fun handleAuth(call: ApplicationCall) {
        try {
            val url = buildAuthUrl(call)
            call.respond(url)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
        }
    }

    suspend fun handleCallback(call: ApplicationCall) {
        try {
            setToken(getToken(call))
            call.respondHtml(HttpStatusCode.OK) {
                head {
                    title("Authentication Complete")
                    style {
                        unsafe {
                            +"body { background-color: #333333; color: #FFFFFF; font-family: sans-serif; width: 100vw; height: 100vh; }"
                            +"p { margin: 1em; }"
                        }
                    }
                    script(ScriptType.textJavaScript) {
                        unsafe {
                            +"setTimeout(function() { window.close(); }, 100);"
                        }
                    }
                }
                body {
                    p {
                        +"Authentication Complete"
                    }
                    p {
                        +"You may safely close this window now."
                    }
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
        }
    }

    abstract fun buildAuthUrl(call: ApplicationCall): String
    abstract suspend fun getToken(call: ApplicationCall): ISyncService.Token
    abstract suspend fun refreshToken(token: ISyncService.Token): ISyncService.Token
    abstract fun mapTableToToken(row: ResultRow): ISyncService.Token

    abstract suspend fun getMe(): ISyncService.Me
    abstract suspend fun getLikedSongs(
        cursor: String? = null,
        continueRequest: suspend (List<ISyncService.LikedSong>) -> Boolean = { true }
    ): Flow<ISyncService.LikedSong>
}