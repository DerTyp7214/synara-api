package dev.dertyp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PerUserChannelsTest {

    private class Subscriber(val job: Job, val received: MutableList<Int>, val firstProbe: CompletableDeferred<Unit>)

    private fun dropOldest() = PerUserChannels<String, Int> {
        MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    private fun CoroutineScope.subscribe(channels: PerUserChannels<String, Int>, key: String): Subscriber {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val firstProbe = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            channels.observe(key).collect { value ->
                if (value < 0) firstProbe.complete(Unit) else received.add(value)
            }
        }
        return Subscriber(job, received, firstProbe)
    }

    private suspend fun awaitSubscribed(channels: PerUserChannels<String, Int>, key: String, vararg subscribers: Subscriber) {
        withTimeout(5.seconds) {
            while (subscribers.any { !it.firstProbe.isCompleted }) {
                channels.tryEmit(key, -1)
                delay(1.milliseconds)
            }
        }
    }

    private suspend fun awaitReceived(subscriber: Subscriber, count: Int) {
        withTimeout(5.seconds) {
            while (subscriber.received.size < count) delay(1.milliseconds)
        }
    }

    private fun entries(channels: PerUserChannels<*, *>): Map<*, *> =
        PerUserChannels::class.java.getDeclaredField("entries").apply { isAccessible = true }.get(channels) as Map<*, *>

    private fun lockEntries(channels: PerUserChannels<*, *>): Map<*, *> {
        val locks = PerUserChannels::class.java.getDeclaredField("locks").apply { isAccessible = true }.get(channels)
        return KeyedMutex::class.java.getDeclaredField("entries").apply { isAccessible = true }.get(locks) as Map<*, *>
    }

    @Test
    fun `subscribers of a key receive its events and only its events`() = runBlocking {
        val channels = dropOldest()
        val first = subscribe(channels, "a")
        val second = subscribe(channels, "a")
        val other = subscribe(channels, "b")
        awaitSubscribed(channels, "a", first, second)
        awaitSubscribed(channels, "b", other)

        assertTrue(channels.tryEmit("a", 1))
        channels.emit("a", 2)

        awaitReceived(first, 2)
        awaitReceived(second, 2)
        assertEquals(listOf(1, 2), first.received.toList())
        assertEquals(listOf(1, 2), second.received.toList())
        assertEquals(emptyList<Int>(), other.received.toList())

        listOf(first, second, other).forEach { it.job.cancelAndJoin() }
    }

    @Test
    fun `events without a subscriber are dropped and not replayed`() = runBlocking {
        val channels = dropOldest()

        assertFalse(channels.tryEmit("a", 1))
        channels.emit("a", 2)

        val late = subscribe(channels, "a")
        awaitSubscribed(channels, "a", late)
        channels.emit("a", 3)
        awaitReceived(late, 1)

        assertEquals(listOf(3), late.received.toList())
        late.job.cancelAndJoin()
    }

    @Test
    fun `holders of the same key run one after another`() = runBlocking {
        val channels = dropOldest()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        (1..8).map {
            async(Dispatchers.Default) {
                channels.withLock("a") {
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, now) }
                    delay(10.milliseconds)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxActive.get())
    }

    @Test
    fun `entries are evicted once subscribers, emitters and lock holders are gone`() = runBlocking {
        val channels = dropOldest()

        val subscribers = (0 until 20).map { subscribe(channels, "user-${it % 5}") }
        (0 until 5).forEach { key ->
            awaitSubscribed(channels, "user-$key", *subscribers.filterIndexed { index, _ -> index % 5 == key }.toTypedArray())
        }
        assertEquals(5, entries(channels).size)

        (1..200).map { index ->
            async(Dispatchers.Default) {
                channels.withLock("user-${index % 7}") { channels.tryEmit("user-${index % 7}", index) }
                channels.emit("user-${index % 9}", index)
            }
        }.awaitAll()
        runCatching { channels.withLock("failing") { error("boom") } }

        subscribers.forEach { it.job.cancelAndJoin() }

        assertTrue(entries(channels).isEmpty())
        assertTrue(lockEntries(channels).isEmpty())
    }

    @Test
    fun `no event is lost while subscribers come and go`() = runBlocking {
        val channels = PerUserChannels<String, Int> { MutableSharedFlow(extraBufferCapacity = 4096) }
        val key = "user"

        repeat(300) { round ->
            val leaving = subscribe(channels, key)
            awaitSubscribed(channels, key, leaving)

            val leave = launch(Dispatchers.Default) { leaving.job.cancelAndJoin() }
            val subscriber = subscribe(channels, key)
            awaitSubscribed(channels, key, subscriber)
            leave.join()

            channels.emit(key, round)
            awaitReceived(subscriber, 1)
            assertEquals(listOf(round), subscriber.received.toList())
            subscriber.job.cancelAndJoin()
        }

        val stable = (0 until 3).map { subscribe(channels, key) }
        awaitSubscribed(channels, key, *stable.toTypedArray())
        val churning = AtomicBoolean(true)
        val churn = (0 until 4).map {
            launch(Dispatchers.Default) {
                while (churning.get()) {
                    val transient = subscribe(channels, key)
                    delay(1.milliseconds)
                    transient.job.cancelAndJoin()
                }
            }
        }

        (0 until 4).map { emitter ->
            async(Dispatchers.Default) {
                (0 until 250).forEach { index -> channels.emit(key, emitter * 1000 + index) }
            }
        }.awaitAll()

        val expected = (0 until 4).flatMap { emitter -> (0 until 250).map { emitter * 1000 + it } }.toSet()
        stable.forEach { subscriber ->
            awaitReceived(subscriber, expected.size)
            assertEquals(expected, subscriber.received.toSet())
            assertEquals(expected.size, subscriber.received.size)
        }

        churning.set(false)
        churn.forEach { it.join() }
        stable.forEach { it.job.cancelAndJoin() }
        assertTrue(entries(channels).isEmpty())
    }
}
