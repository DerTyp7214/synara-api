package dev.dertyp.services.sync

import com.github.benmanes.caffeine.cache.Caffeine
import dev.dertyp.core.getUsername
import dev.dertyp.data.User
import dev.dertyp.db.SyncServiceTable
import dev.dertyp.dbQuery
import dev.dertyp.serializers.DateSerializer
import dev.dertyp.services.Service
import dev.dertyp.services.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.html.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.upsert
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
abstract class SyncService(
    private val database: Database,
    protected val environment: ApplicationEnvironment,
    protected val user: User
) : Service() {
    abstract val clientIdConfigPath: String
    abstract val clientSecretConfigPath: String
    abstract val scopes: List<String>

    private val serviceType: SyncServiceType
    private val redirectPath: String by lazy { "/sync/${serviceType.name}/callback" }

    protected val clientId by lazy { environment.config.propertyOrNull(clientIdConfigPath)?.getString() }
    protected val clientSecret by lazy { environment.config.propertyOrNull(clientSecretConfigPath)?.getString() }

    init {
        serviceType = when {
            this is TidalSyncService -> SyncServiceType.tidal
            else -> SyncServiceType.unknown
        }
    }

    companion object {
        val authFlowCache = Caffeine.newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build<String, String>()

        private suspend fun getInstance(
            name: String?,
            database: Database,
            call: ApplicationCall,
            username: String? = null
        ): SyncService? {
            return when (name) {
                SyncServiceType.tidal.name -> {
                    val environment = call.application.environment
                    val user = UserService(database, environment)
                        .findUserByUsername(username ?: call.getUsername())
                    if (user == null) return null
                    TidalSyncService(database, environment, user)
                }

                else -> null
            }
        }

        suspend fun getInstance(call: ApplicationCall, database: Database, username: String? = null): SyncService {
            val instance = getInstance(call.parameters["service"], database, call, username)
            if (instance == null) throw IllegalStateException("Service not found")
            return instance
        }

        suspend fun handleAuth(call: ApplicationCall, database: Database) {
            val service = getInstance(call.parameters["service"], database, call)

            if (service == null)
                return call.respond(HttpStatusCode.BadRequest, "Invalid Service")

            service.handleAuth(call)
        }

        suspend fun handleCallback(call: ApplicationCall, database: Database, username: String?) {
            val service = getInstance(call.parameters["service"], database, call, username)

            if (service == null)
                return call.respond(HttpStatusCode.BadRequest, "Invalid Service")

            service.handleCallback(call)
        }
    }

    protected suspend fun setToken(token: Token) {
        if (token.createdAt != null) {
            dbQuery {
                SyncServiceTable.upsert {
                    it[this.name] = serviceType.name
                    it[this.ownerId] = user.id
                    it[this.scope] = token.scope ?: ""
                    it[this.accessToken] = token.accessToken
                    it[this.refreshToken] = token.refreshToken
                    it[this.expiresIn] = token.expiresIn
                    it[this.tokenType] = token.tokenType
                    it[this.userId] = token.userId
                    it[this.createdAt] = token.createdAt!!
                }
            }
        }
    }

    protected suspend fun getToken(): Token? = dbQuery {
        SyncServiceTable
            .select(SyncServiceTable.columns)
            .where { SyncServiceTable.ownerId eq user.id }
            .andWhere { SyncServiceTable.name eq serviceType.name }
            .map(::mapTableToToken)
    }.singleOrNull()

    protected suspend fun getAccessToken(): Token? {
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
            call.respondRedirect(url)
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
    abstract suspend fun getToken(call: ApplicationCall): Token
    abstract suspend fun refreshToken(token: Token): Token
    abstract fun mapTableToToken(row: ResultRow): Token

    abstract suspend fun getMe(): Me
    abstract suspend fun getLikedSongs(
        cursor: String? = null,
        continueRequest: suspend (List<LikedSong>) -> Boolean = { true }
    ): Flow<LikedSong>
    abstract suspend fun getAlbumIdByTrackId(trackId: String): String?
    abstract suspend fun getImageUrlByAlbumId(albumId: String): List<Image>

    interface Token {
        val scope: String?
        val accessToken: String
        val refreshToken: String
        val expiresIn: Int
        val tokenType: String
        val userId: Long
        val createdAt: Long?
    }

    @Suppress("EnumEntryName")
    enum class SyncServiceType {
        tidal,
        unknown
    }

    @Serializable
    data class Me(
        val id: String,
        val username: String,
        val email: String,
    )

    @Serializable
    data class LikedSong(
        val id: String,
        val title: String,
        @Serializable(with = DateSerializer::class)
        val addedAt: Date,
        val explicit: Boolean,
    )

    @Serializable
    data class Image(
        val url: String,
        val width: Int,
        val height: Int,
    )

    @Serializable
    data class Album(
        val id: String,
        val title: String,
        val numberOfVolumes: Long,
        val numberOfItems: Long,
        val duration: Long,
        val explicit: Boolean,
        @Serializable(with = DateSerializer::class)
        val releaseDate: Date,
        val copyright: String,
        val coverUrl: String,
    )
}