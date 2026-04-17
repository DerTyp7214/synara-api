package dev.dertyp

import dev.dertyp.core.withSynaraPack
import dev.dertyp.serializers.SynaraNegotiation
import io.ktor.server.request.header
import io.mockk.every
import io.mockk.mockk
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SynaraPackHeaderTest {

    @Test
    fun `withSynaraPack should enable negotiation when header is true`() {
        val route = mockk<KrpcRoute>()
        every { route.call.request.header("X-Synara-Pack") } returns "true"
        
        route.withSynaraPack()
        assertTrue(SynaraNegotiation.isEnabled)
    }

    @Test
    fun `withSynaraPack should disable negotiation when header is missing`() {
        val route = mockk<KrpcRoute>()
        every { route.call.request.header("X-Synara-Pack") } returns null
        
        route.withSynaraPack()
        assertFalse(SynaraNegotiation.isEnabled)
    }

    @Test
    fun `withSynaraPack should disable negotiation when header is false`() {
        val route = mockk<KrpcRoute>()
        every { route.call.request.header("X-Synara-Pack") } returns "false"
        
        route.withSynaraPack()
        assertFalse(SynaraNegotiation.isEnabled)
    }
}
