package dev.dertyp.plugins

import io.ktor.server.testing.testApplication
import io.mockk.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class JmDNSPluginTest {

    @BeforeTest
    fun setup() {
        mockkStatic(JmDNS::class)
        mockkStatic(ServiceInfo::class)
        mockkStatic(InetAddress::class)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `should register and unregister JmDNS service`() {
        val mockJmDNS = mockk<JmDNS>(relaxed = true)
        val mockServiceInfo = mockk<ServiceInfo>(relaxed = true)
        val mockAddress = mockk<InetAddress>()

        every { InetAddress.getLocalHost() } returns mockAddress
        every { mockAddress.hostName } returns "test-host"
        every { JmDNS.create(any<InetAddress>(), any<String>()) } returns mockJmDNS
        every { 
            ServiceInfo.create(
                any<String>(), 
                any<String>(), 
                any<Int>(), 
                any<Int>(), 
                any<Int>(), 
                any<Map<String, String>>()
            ) 
        } returns mockServiceInfo

        testApplication {
            install(JmDNSPlugin) {
                serviceName = "test-service"
                serviceType = "_test._tcp.local."
            }
        }

        verify { JmDNS.create(mockAddress, "test-host") }
        verify { 
            ServiceInfo.create(
                "_test._tcp.local.",
                "test-service",
                8080,
                0, 0,
                emptyMap<String, String>()
            ) 
        }
        verify { mockJmDNS.registerService(mockServiceInfo) }
        verify { mockJmDNS.unregisterAllServices() }
        verify { mockJmDNS.close() }
    }
}
