package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.TidalSessionFormat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

data class TidalAuthData(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAt: Long?,
    val userId: String?,
    val countryCode: String?,
    val extra: JsonObject,
)

object TidalAuthFormats {
    private val tiddlKeys = setOf("token", "refresh_token", "expires_at", "user_id", "country_code")
    private val tdnKeys = setOf("token_type", "access_token", "refresh_token", "expiry_time")

    fun role(format: TidalSessionFormat): String = when (format) {
        TidalSessionFormat.TIDDL -> CredentialFileRoles.TIDDL_AUTH
        TidalSessionFormat.TDN -> CredentialFileRoles.TDN_TOKEN
    }

    fun parse(format: TidalSessionFormat, content: String): TidalAuthData {
        val obj = try {
            BrokerJson.json.parseToJsonElement(content).jsonObject
        } catch (e: SerializationException) {
            throw CredentialException(CredentialErrorCode.INVALID, "${role(format)} is not valid JSON")
        } catch (e: IllegalArgumentException) {
            throw CredentialException(CredentialErrorCode.INVALID, "${role(format)} is not a JSON object")
        }
        return when (format) {
            TidalSessionFormat.TIDDL -> TidalAuthData(
                accessToken = obj.string("token").nonBlank(),
                refreshToken = obj.string("refresh_token").nonBlank(),
                expiresAt = obj.long("expires_at")?.takeIf { it > 0 }?.let { it * 1000 },
                userId = obj.string("user_id").nonBlank(),
                countryCode = obj.string("country_code").nonBlank(),
                extra = JsonObject(obj.filterKeys { it !in tiddlKeys }),
            )
            TidalSessionFormat.TDN -> TidalAuthData(
                accessToken = obj.string("access_token").nonBlank(),
                refreshToken = obj.string("refresh_token").nonBlank(),
                expiresAt = (obj["expiry_time"] as? JsonPrimitive)?.doubleOrNull
                    ?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
                userId = null,
                countryCode = null,
                extra = JsonObject(obj.filterKeys { it !in tdnKeys }),
            )
        }
    }

    fun render(secret: TidalSessionSecret): String {
        val obj = buildJsonObject {
            secret.extra.forEach { (key, value) -> put(key, value) }
            when (secret.format) {
                TidalSessionFormat.TIDDL -> {
                    put("token", secret.accessToken.toJson())
                    put("refresh_token", secret.refreshToken.toJson())
                    put("expires_at", JsonPrimitive((secret.expiresAt ?: 0L) / 1000))
                    put("user_id", secret.userId.toJson())
                    put("country_code", secret.countryCode.toJson())
                }
                TidalSessionFormat.TDN -> {
                    put("token_type", JsonPrimitive("Bearer"))
                    put("access_token", secret.accessToken.toJson())
                    put("refresh_token", secret.refreshToken.toJson())
                    put("expiry_time", JsonPrimitive((secret.expiresAt ?: 0L) / 1000.0))
                }
            }
        }
        return obj.toString()
    }

    fun adopt(secret: TidalSessionSecret, data: TidalAuthData): TidalSessionSecret = secret.copy(
        accessToken = data.accessToken ?: secret.accessToken,
        refreshToken = data.refreshToken ?: secret.refreshToken,
        expiresAt = data.expiresAt ?: secret.expiresAt,
        userId = data.userId ?: secret.userId,
        countryCode = data.countryCode ?: secret.countryCode,
        extra = data.extra,
    )

    private fun String?.nonBlank() = this?.takeIf { it.isNotBlank() }

    private fun String?.toJson() = this?.let { JsonPrimitive(it) } ?: JsonNull
}
