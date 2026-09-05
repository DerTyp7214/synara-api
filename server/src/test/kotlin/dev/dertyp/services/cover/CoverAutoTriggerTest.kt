package dev.dertyp.services.cover

import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.services.HookService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class CoverAutoTriggerTest {
    private val hooks = HookService()

    private fun setup() {
        startKoin { modules(module { single<HookBus> { hooks } }) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `bursts of changes collapse into one generation per target after the debounce`() = runBlocking {
        setup()
        val service = mockk<CoverGenerationService>()
        val enqueued = CopyOnWriteArrayList<CoverTarget>()
        coEvery { service.row(any()) } answers {
            val target = firstArg<CoverTarget>()
            CoverGenerationService.TargetRow(target, "name", UUID.randomUUID(), null, null, null, null)
        }
        every { service.enqueueAuto(any(), any(), any()) } answers { enqueued += firstArg<CoverTarget>(); null }
        val trigger = CoverAutoTrigger(service, CoverConfig("unused", false, true, 150.milliseconds))
        trigger.startService()

        val playlist = UUID.randomUUID()
        val collection = UUID.randomUUID()
        repeat(5) { hooks.emit(HookEvent.PlaylistChanged(playlist)) }
        hooks.emit(HookEvent.CollectionChanged(collection))
        delay(50)
        assertEquals(0, enqueued.size)
        delay(400)
        assertEquals(2, enqueued.size)
        assertEquals(setOf(CoverTarget(CoverTargetType.PLAYLIST, playlist), CoverTarget(CoverTargetType.COLLECTION, collection)), enqueued.toSet())
        assertEquals(0, trigger.pendingCount())
        trigger.stopService()
    }

    @Test
    fun `disabled auto generation registers nothing`() = runBlocking {
        setup()
        val service = mockk<CoverGenerationService>()
        val trigger = CoverAutoTrigger(service, CoverConfig("unused", false, false, 10.milliseconds))
        trigger.startService()
        hooks.emit(HookEvent.PlaylistChanged(UUID.randomUUID()))
        delay(100)
        assertEquals(0, trigger.pendingCount())
    }
}
