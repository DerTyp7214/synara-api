package dev.dertyp.services.hue

import dev.dertyp.ApiClient
import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.InetAddress
import javax.jmdns.JmDNS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HueDiscoveryService : Service() {
    @Serializable
    private data class CloudBridge(val id: String, val internalipaddress: String, val port: Int? = null)

    @Volatile
    private var cache: Pair<Long, List<HueBridgeCandidate>>? = null

    fun cached(): List<HueBridgeCandidate> = cache?.second ?: emptyList()

    suspend fun discover(timeout: Duration = 3.seconds, force: Boolean = false): List<HueBridgeCandidate> {
        val now = System.currentTimeMillis()
        cache?.let { (at, result) -> if (!force && now - at < CACHE_TTL.inWholeMilliseconds) return result }
        val fromMdns = mdns(timeout)
        val fromCloud = if (fromMdns.isEmpty()) cloud() else emptyList()
        val merged = (fromMdns + fromCloud).distinctBy { it.ip }
        cache = now to merged
        return merged
    }

    private suspend fun mdns(timeout: Duration): List<HueBridgeCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            JmDNS.create(InetAddress.getLocalHost()).use { jmdns ->
                jmdns.list(SERVICE_TYPE, timeout.inWholeMilliseconds).mapNotNull { info ->
                    val ip = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.hostAddresses.firstOrNull() ?: return@mapNotNull null
                    HueBridgeCandidate(
                        bridgeId = info.getPropertyString("bridgeid")?.lowercase(),
                        ip = ip,
                        modelId = info.getPropertyString("modelid"),
                    )
                }
            }
        }.onFailure { logger.warn("Hue mDNS discovery failed: ${it.message}") }.getOrDefault(emptyList())
    }

    private suspend fun cloud(): List<HueBridgeCandidate> =
        runCatching {
            ApiClient.instance.get(CLOUD_DISCOVERY_URL).body<List<CloudBridge>>().map {
                HueBridgeCandidate(bridgeId = it.id.lowercase(), ip = it.internalipaddress)
            }
        }.onFailure { logger.warn("Hue cloud discovery failed: ${it.message}") }.getOrDefault(emptyList())

    companion object {
        const val SERVICE_TYPE = "_hue._tcp.local."
        const val CLOUD_DISCOVERY_URL = "https://discovery.meethue.com/"
        private val CACHE_TTL = 10.minutes
    }
}
