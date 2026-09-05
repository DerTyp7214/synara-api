package dev.dertyp.services.hue

import dev.dertyp.ApiClient
import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.plugins.JmDNSHolder
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import javax.jmdns.JmDNS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HueDiscoveryService : Service() {
    @Serializable
    private data class CloudBridge(val id: String, val internalipaddress: String, val port: Int? = null)

    @Volatile
    private var cache: Pair<Long, List<HueBridgeCandidate>>? = null

    internal var mdnsLister: suspend (Duration) -> List<HueBridgeCandidate>? = { timeout -> mdns(timeout) }
    internal var cloudLister: suspend () -> List<HueBridgeCandidate> = { cloud() }
    internal var prober: suspend (String) -> HueBridgeConfig? = { ip -> probe(ip) }

    private val probeLimit = Semaphore(PROBE_CONCURRENCY)

    fun cached(): List<HueBridgeCandidate> = cache?.second ?: emptyList()

    suspend fun discover(timeout: Duration = 3.seconds, force: Boolean = false): List<HueBridgeCandidate> {
        val now = System.currentTimeMillis()
        cache?.let { (at, result) -> if (!force && now - at < CACHE_TTL.inWholeMilliseconds) return result }
        val found = mdnsLister(timeout)
        val candidates = (found.orEmpty() + cloudLister()).distinctBy { it.ip }
        val verified = coroutineScope {
            candidates.map { candidate ->
                async {
                    val config = probeLimit.withPermit {
                        withTimeoutOrNull(PROBE_TIMEOUT.inWholeMilliseconds) { prober(candidate.ip) }
                    } ?: return@async null
                    candidate.copy(
                        bridgeId = config.bridgeid?.lowercase() ?: candidate.bridgeId,
                        modelId = config.modelid ?: candidate.modelId,
                        name = config.name ?: candidate.name,
                    )
                }
            }.mapNotNull { it.await() }
        }
        val merged = verified.distinctBy { it.bridgeId ?: it.ip }
        cache = now to merged
        return merged
    }

    private suspend fun mdns(timeout: Duration): List<HueBridgeCandidate>? = withContext(Dispatchers.IO) {
        val direct = runCatching { MdnsQuery.responders(SERVICE_TYPE, timeout).map { HueBridgeCandidate(ip = it) } }
            .onFailure { logger.warn("Hue mDNS query failed: ${it.message}") }
            .getOrNull()
        val shared = JmDNSHolder.instance?.let { jmdns ->
            runCatching { jmdns.listHue(timeout) }
                .onFailure { logger.warn("Hue mDNS browse via the shared responder failed: ${it.message}") }
                .getOrNull()
        }
        if (direct == null && shared == null) null else (shared.orEmpty() + direct.orEmpty()).distinctBy { it.ip }
    }

    private fun JmDNS.listHue(timeout: Duration): List<HueBridgeCandidate> =
        list(SERVICE_TYPE, timeout.inWholeMilliseconds).mapNotNull { info ->
            val ip = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.hostAddresses.firstOrNull() ?: return@mapNotNull null
            HueBridgeCandidate(
                bridgeId = info.getPropertyString("bridgeid")?.lowercase(),
                ip = ip,
                modelId = info.getPropertyString("modelid"),
            )
        }

    private suspend fun cloud(): List<HueBridgeCandidate> =
        runCatching {
            ApiClient.instance.get(CLOUD_DISCOVERY_URL).body<List<CloudBridge>>().map {
                HueBridgeCandidate(bridgeId = it.id.lowercase(), ip = it.internalipaddress)
            }
        }.onFailure { logger.warn("Hue cloud discovery failed: ${it.message}") }.getOrDefault(emptyList())

    private suspend fun probe(ip: String): HueBridgeConfig? {
        val client = HueBridgeClient(ip, null, null, null)
        return try {
            client.config().takeIf { it.bridgeid != null }
        } catch (e: Exception) {
            logger.debug("Hue candidate $ip did not answer: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_hue._tcp.local."
        const val CLOUD_DISCOVERY_URL = "https://discovery.meethue.com/"
        private val CACHE_TTL = 10.minutes
        private val PROBE_TIMEOUT = 4.seconds
        private const val PROBE_CONCURRENCY = 32
    }
}
