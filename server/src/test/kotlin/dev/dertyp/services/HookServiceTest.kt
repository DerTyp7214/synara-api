package dev.dertyp.services

import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import dev.dertyp.randomPlatformUUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HookServiceTest {

    @Test
    fun `emit invokes matching handler with payload`() = runBlocking {
        val hooks = HookService()
        val received = CompletableDeferred<HookEvent.ListenIngested>()
        hooks.on<HookEvent.ListenIngested> { received.complete(it) }

        val id = randomPlatformUUID()
        hooks.emit(HookEvent.ListenIngested(id, 5))

        val event = withTimeout(2.seconds) { received.await() }
        assertEquals(id, event.listenBrainzUserId)
        assertEquals(5, event.count)
    }

    @Test
    fun `handler exception is isolated from other handlers`() = runBlocking {
        val hooks = HookService()
        val second = CompletableDeferred<Unit>()
        hooks.on<HookEvent.PlaylistChanged> { throw RuntimeException("boom") }
        hooks.on<HookEvent.PlaylistChanged> { second.complete(Unit) }

        hooks.emit(HookEvent.PlaylistChanged(randomPlatformUUID()))

        withTimeout(2.seconds) { second.await() }
    }

    @Test
    fun `cancelled registration stops receiving`() = runBlocking {
        val hooks = HookService()
        var count = 0
        val registration = hooks.on<HookEvent.PlaylistChanged> { count++ }
        registration.cancel()

        hooks.emit(HookEvent.PlaylistChanged(randomPlatformUUID()))
        delay(200.milliseconds)

        assertEquals(0, count)
    }
}
