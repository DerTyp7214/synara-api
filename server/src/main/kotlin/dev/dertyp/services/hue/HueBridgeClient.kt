package dev.dertyp.services.hue

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

class HueRateLimited : Exception("Hue bridge rate limit exceeded")

class HueBridgeException(message: String) : Exception(message)

interface HueBridgeApi {
    suspend fun config(): HueBridgeConfig
    suspend fun pair(deviceType: String): HuePairSuccess?
    suspend fun bridge(): ClipBridge
    suspend fun lights(): List<ClipLight>
    suspend fun rooms(): List<ClipGroup>
    suspend fun zones(): List<ClipGroup>
    suspend fun groupedLights(): List<ClipGroupedLight>
    suspend fun putLight(id: String, update: LightUpdate)
    suspend fun putGroupedLight(id: String, update: LightUpdate)
    fun close()
}

@OptIn(ExperimentalSerializationApi::class)
class HueBridgeClient(
    val ip: String,
    val bridgeId: String?,
    private val applicationKey: String?,
    pinnedFingerprint: String?,
    onFingerprint: (String) -> Unit = {},
) : HueBridgeApi {
    private val trustManager = HueTrust.PinnedTrustManager(pinnedFingerprint, onFingerprint)
    private val base = "https://$ip"

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(HueTrust.sslContext(trustManager).socketFactory, trustManager)
                hostnameVerifier(HueTrust.hostnameVerifier(bridgeId))
            }
        }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 5_000
        }
        expectSuccess = false
    }

    override suspend fun config(): HueBridgeConfig {
        val response = client.get("$base/api/0/config")
        check(response)
        return response.body()
    }

    override suspend fun pair(deviceType: String): HuePairSuccess? {
        val response = client.post("$base/api") {
            contentType(ContentType.Application.Json)
            setBody(HuePairRequest(deviceType))
        }
        if (!response.status.isSuccess()) throw HueBridgeException("Pairing request failed: ${response.status}")
        val entries = response.body<List<HuePairResponseEntry>>()
        entries.firstNotNullOfOrNull { it.success }?.let { return it }
        val error = entries.firstNotNullOfOrNull { it.error } ?: throw HueBridgeException("Empty pairing response")
        if (error.type == LINK_BUTTON_NOT_PRESSED) return null
        throw HueBridgeException(error.description ?: "Pairing error ${error.type}")
    }

    override suspend fun bridge(): ClipBridge =
        resource<ClipBridge>("bridge").firstOrNull() ?: throw HueBridgeException("Bridge resource missing")

    override suspend fun lights(): List<ClipLight> = resource("light")
    override suspend fun rooms(): List<ClipGroup> = resource("room")
    override suspend fun zones(): List<ClipGroup> = resource("zone")
    override suspend fun groupedLights(): List<ClipGroupedLight> = resource("grouped_light")

    override suspend fun putLight(id: String, update: LightUpdate) = put("light", id, update)

    override suspend fun putGroupedLight(id: String, update: LightUpdate) = put("grouped_light", id, update)

    override fun close() = client.close()

    private suspend inline fun <reified T> resource(type: String): List<T> {
        val response = client.get("$base/clip/v2/resource/$type") { auth() }
        check(response)
        return response.body<ClipResponse<T>>().data
    }

    private suspend fun put(type: String, id: String, update: LightUpdate) {
        val response = client.put("$base/clip/v2/resource/$type/$id") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(update)
        }
        check(response)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        applicationKey?.let { header(APPLICATION_KEY_HEADER, it) }
    }

    private suspend fun check(response: HttpResponse) {
        if (response.status == HttpStatusCode.TooManyRequests) throw HueRateLimited()
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            throw HueBridgeException("Bridge responded ${response.status.value}: ${text.take(200)}")
        }
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        const val APPLICATION_KEY_HEADER = "hue-application-key"
        const val LINK_BUTTON_NOT_PRESSED = 101

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
