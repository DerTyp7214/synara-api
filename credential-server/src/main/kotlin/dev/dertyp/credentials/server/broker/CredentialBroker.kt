package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.ResolvedCredential
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.util.Base64

data class BrokerResolution<S : StoredSecret>(
    val credential: ResolvedCredential,
    val updatedSecret: S?,
    val state: CredentialStateUpdate,
)

interface CredentialBroker<S : StoredSecret> {
    suspend fun resolve(name: String, secret: S): BrokerResolution<S>

    fun invalidate(name: String) {}
}

object Fingerprints {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray())

    fun ofFiles(files: List<CredentialFile>): String =
        sha256Hex(files.map { FileContents.decode(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes })
}

object FileContents {
    fun decode(file: CredentialFile): ByteArray = try {
        Base64.getDecoder().decode(file.contentBase64)
    } catch (e: IllegalArgumentException) {
        throw CredentialException(CredentialErrorCode.INVALID, "File ${file.role} is not valid base64")
    }

    fun decodeText(file: CredentialFile): String = decode(file).decodeToString()

    fun encode(role: String, text: String) = CredentialFile(role, Base64.getEncoder().encodeToString(text.toByteArray()))
}

internal suspend fun HttpResponse.jsonBodyOrNull(): JsonObject? = runCatching {
    BrokerJson.json.parseToJsonElement(bodyAsText()).jsonObject
}.getOrNull()

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long? =
    (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

internal object BrokerJson {
    val json = Json { ignoreUnknownKeys = true }
}
