package dev.dertyp.services.hue

import dev.dertyp.data.HueBridgeCandidate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class HueDiscoveryServiceTest {
    @Test
    fun `stale cloud entries are dropped and reachable ones are verified and deduplicated`() = runBlocking {
        val service = HueDiscoveryService()
        service.mdnsLister = { listOf(HueBridgeCandidate(bridgeId = "001788FFFE0000AA", ip = "192.168.178.21", modelId = "BSB002")) }
        service.cloudLister = {
            listOf("192.168.178.21", "192.168.178.20", "192.168.178.184", "192.168.178.137", "192.168.178.46")
                .map { HueBridgeCandidate(bridgeId = "001788fffe0000aa", ip = it) }
        }
        service.prober = { ip ->
            when (ip) {
                "192.168.178.21" -> HueBridgeConfig(name = "Living room", bridgeid = "001788FFFE0000AA", modelid = "BSB002")
                "192.168.178.46" -> HueBridgeConfig(name = "Old", bridgeid = "001788FFFE0000AA", modelid = "BSB002")
                else -> null
            }
        }

        val found = service.discover(force = true)

        assertEquals(1, found.size)
        val bridge = found.single()
        assertEquals("192.168.178.21", bridge.ip)
        assertEquals("001788fffe0000aa", bridge.bridgeId)
        assertEquals("Living room", bridge.name)
        assertEquals(found, service.cached())
    }

    @Test
    fun `results are cached until forced`() = runBlocking {
        val service = HueDiscoveryService()
        val calls = AtomicInteger()
        service.mdnsLister = { calls.incrementAndGet(); listOf(HueBridgeCandidate(ip = "10.0.0.5")) }
        service.cloudLister = { emptyList() }
        service.prober = { HueBridgeConfig(bridgeid = "abc") }

        assertEquals(1, service.discover().size)
        assertEquals(1, service.discover().size)
        assertEquals(1, calls.get())
        assertEquals(1, service.discover(force = true).size)
        assertEquals(2, calls.get())
    }

    @Test
    fun `candidates that never answer are excluded and no probe answer means empty`() = runBlocking {
        val service = HueDiscoveryService()
        service.mdnsLister = { emptyList() }
        service.cloudLister = { listOf(HueBridgeCandidate(bridgeId = "x", ip = "10.0.0.1"), HueBridgeCandidate(bridgeId = "y", ip = "10.0.0.2")) }
        service.prober = { null }
        assertTrue(service.discover(force = true).isEmpty())
    }
}
