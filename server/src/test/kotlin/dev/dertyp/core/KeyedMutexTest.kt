package dev.dertyp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KeyedMutexTest {

    @Test
    fun `holders of the same key run one after another`() = runBlocking {
        val locks = KeyedMutex<String>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        (1..8).map {
            async(Dispatchers.Default) {
                locks.withLock("song.flac") {
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, now) }
                    delay(20.milliseconds)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxActive.get())
    }

    @Test
    fun `different keys run in parallel`() = runBlocking {
        val locks = KeyedMutex<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            locks.withLock("a.flac") {
                firstEntered.complete(Unit)
                withTimeout(5.seconds) { secondEntered.await() }
            }
        }
        val second = async(Dispatchers.Default) {
            locks.withLock("b.flac") {
                secondEntered.complete(Unit)
                withTimeout(5.seconds) { firstEntered.await() }
            }
        }

        first.await()
        second.await()
    }

    @Test
    fun `a key can be locked again after a failing holder released it`() = runBlocking {
        val locks = KeyedMutex<String>()

        runCatching { locks.withLock("song.flac") { error("boom") } }
        val result = withTimeout(5.seconds) { locks.withLock("song.flac") { "ok" } }

        assertEquals("ok", result)
    }

    @Test
    fun `the block result is returned`() = runBlocking {
        val locks = KeyedMutex<Int>()
        var ran = false

        val result = locks.withLock(1) {
            ran = true
            42
        }

        assertEquals(42, result)
        assertTrue(ran)
    }

    @Test
    fun `released keys are evicted`() = runBlocking {
        val locks = KeyedMutex<String>()

        (1..200).map { index ->
            async(Dispatchers.Default) {
                locks.withLock("song-${index % 20}.flac") { delay(1.milliseconds) }
            }
        }.awaitAll()
        runCatching { locks.withLock("failing.flac") { error("boom") } }

        val entries =
            KeyedMutex::class.java.getDeclaredField("entries").apply { isAccessible = true }.get(locks) as Map<*, *>
        assertTrue(entries.isEmpty())
    }
}
