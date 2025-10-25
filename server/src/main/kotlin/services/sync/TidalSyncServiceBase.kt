package dev.dertyp.services.sync

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import dev.dertyp.ApiClient
import dev.dertyp.core.getUsername
import dev.dertyp.core.parameters
import dev.dertyp.data.User
import dev.dertyp.db.SyncServiceTable
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.util.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import java.io.File
import java.util.*
import kotlin.time.Duration.Companion.seconds

abstract class TidalSyncServiceBase(
    database: Database,
    environment: ApplicationEnvironment,
    user: User
) : SyncService(database, environment, user) {
    override val clientIdConfigPath: String = "tidal.clientId"
    override val clientSecretConfigPath: String = "tidal.clientSecret"
    override val scopes: List<String> = listOf(
        "collection.read",
        "user.read",
        "entitlements.read",
        "playlists.read",
        "recommendations.read",
        "search.read"
    )

    override fun buildAuthUrl(call: ApplicationCall): String {
        if (clientId == null) throw IllegalArgumentException("clientId must be specified!")

        val state = call.getUsername()
        val codeVerifier = generateCodeVerifier()

        authFlowCache.put(state, codeVerifier)

        return url {
            protocol = URLProtocol.HTTPS
            host = "login.tidal.com"
            encodedPath = "/authorize"
            parameters {
                append("client_id", clientId!!)
                append("response_type", "code")
                append("redirect_uri", generateRedirectUrl(call))
                append("scope", scopes.joinToString(" "))
                append("state", state)
                append("code_challenge", generateCodeChallenge(codeVerifier))
                append("code_challenge_method", "S256")
            }
        }
    }

    override suspend fun getToken(call: ApplicationCall): Token {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]

        if (code == null || state == null)
            throw IllegalArgumentException("Code and State must be specified!")

        val codeVerifier =
            authFlowCache.getIfPresent(state) ?: throw IllegalArgumentException("State must be specified!")

        val url = url {
            protocol = URLProtocol.HTTPS
            host = "auth.tidal.com"
            encodedPath = "/v1/oauth2/token"
        }

        return ApiClient.instance.post(url) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    parametersOf(
                        "grant_type" to listOf("authorization_code"),
                        "client_id" to listOf(clientId!!),
                        "code" to listOf(code),
                        "redirect_uri" to listOf(generateRedirectUrl(call)),
                        "code_verifier" to listOf(codeVerifier)
                    )
                )
            )
        }.body<TidalTokenResponse>().copy(
            createdAt = System.currentTimeMillis(),
        )
    }

    override suspend fun refreshToken(token: Token): Token {
        val url = url {
            protocol = URLProtocol.HTTPS
            host = "auth.tidal.com"
            encodedPath = "/v1/oauth2/token"
        }

        return ApiClient.instance.post(url) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    parametersOf(
                        "grant_type" to listOf("refresh_token"),
                        "refresh_token" to listOf(token.refreshToken)
                    )
                )
            )
        }.body<TidalTokenResponse>().copy(
            userId = token.userId,
            refreshToken = token.refreshToken,
            createdAt = System.currentTimeMillis(),
        )
    }

    override fun mapTableToToken(row: ResultRow): Token = TidalTokenResponse(
        scope = row[SyncServiceTable.scope],
        accessToken = row[SyncServiceTable.accessToken],
        refreshToken = row[SyncServiceTable.refreshToken],
        expiresIn = row[SyncServiceTable.expiresIn],
        tokenType = row[SyncServiceTable.tokenType],
        userId = row[SyncServiceTable.userId],
        createdAt = row[SyncServiceTable.createdAt],
    )

    fun setTdnToken(token: Token) {
        val tdnConfigPath = environment.config.propertyOrNull("tidal.tdnTokenPath")?.getString()
        if (tdnConfigPath == null) return

        val tokenJson = mapOf<String, Any?>(
            "token_type" to token.tokenType,
            "access_token" to token.accessToken,
            "refresh_token" to token.refreshToken,
            "expiry_time" to String.format(
                Locale.ROOT, "%.6f",
                (token.expiresIn.seconds.inWholeMilliseconds + (token.createdAt ?: 0)) / 1000.0
            ).toBigDecimal(),
        )

        File(tdnConfigPath).writeText(
            GsonBuilder().setPrettyPrinting().create().toJson(tokenJson)
        )
    }

    @Serializable
    data class TidalTokenResponse(
        @SerializedName("scope") override val scope: String,
        @SerializedName("access_token") override val accessToken: String,
        @SerializedName("refresh_token") override val refreshToken: String,
        @SerializedName("expires_in") override val expiresIn: Int,
        @SerializedName("token_type") override val tokenType: String,
        @SerializedName("user_id") override val userId: Long,
        override val createdAt: Long? = null,
    ) : Token
}