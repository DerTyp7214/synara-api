package dev.dertyp.utils

import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.rpc.annotations.Cached
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface CachingProbe {
    @Cached("5m")
    suspend fun value(id: Int): String

    @Cached("1h")
    suspend fun nullable(id: Int): String?

    @Cached("5m")
    suspend fun failing(id: Int): String

    suspend fun uncached(id: Int): String

    @Cached("5m")
    fun blocking(id: Int): String
}

class CountingCachingProbe : CachingProbe {
    val calls = AtomicInteger()

    override suspend fun value(id: Int): String {
        calls.incrementAndGet()
        delay(10)
        return "fresh-$id"
    }

    override suspend fun nullable(id: Int): String? {
        calls.incrementAndGet()
        delay(10)
        return null
    }

    override suspend fun failing(id: Int): String {
        calls.incrementAndGet()
        delay(10)
        throw IllegalStateException("failed-$id")
    }

    override suspend fun uncached(id: Int): String {
        calls.incrementAndGet()
        return "uncached-$id"
    }

    override fun blocking(id: Int): String {
        calls.incrementAndGet()
        return "blocking-$id"
    }
}

class CachingTest {
    private lateinit var cacheProvider: RedisCacheProvider
    private lateinit var target: CountingCachingProbe
    private lateinit var proxy: CachingProbe

    private fun key(method: String, id: Int) =
        "rpc_cache:${CachingProbe::class.java.name}:$method:${hashArgs(arrayOf(id))}"

    @BeforeEach
    fun setUp() {
        cacheProvider = mockk(relaxed = true)
        coEvery { cacheProvider.getCache(any()) } returns null
        startKoin { modules(module { single { cacheProvider } }) }
        target = CountingCachingProbe()
        proxy = target.withCaching<CachingProbe>()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `a cache hit returns the stored value without calling the target`() = runBlocking {
        coEvery { cacheProvider.getCache(key("value", 7)) } returns "cached-7"

        assertEquals("cached-7", proxy.value(7))
        assertEquals(0, target.calls.get())
        coVerify(exactly = 0) { cacheProvider.setCache(any(), any(), any()) }
    }

    @Test
    fun `a cache miss calls the suspending target and stores its result under the same key and ttl`() = runBlocking {
        assertEquals("fresh-3", proxy.value(3))

        assertEquals(1, target.calls.get())
        coVerify(exactly = 1) { cacheProvider.getCache(key("value", 3)) }
        coVerify(exactly = 1) { cacheProvider.setCache(key("value", 3), "fresh-3", 5.minutes) }
    }

    @Test
    fun `a null result is returned and not stored`() = runBlocking {
        assertNull(proxy.nullable(1))

        assertEquals(1, target.calls.get())
        coVerify(exactly = 0) { cacheProvider.setCache(any(), any(), any()) }
    }

    @Test
    fun `a failing target propagates its exception and stores nothing`() {
        val error = assertThrows<IllegalStateException> { runBlocking { proxy.failing(4) } }

        assertEquals("failed-4", error.message)
        coVerify(exactly = 0) { cacheProvider.setCache(any(), any(), any()) }
    }

    @Test
    fun `functions without the annotation bypass the cache`() = runBlocking {
        assertEquals("uncached-2", proxy.uncached(2))

        assertEquals(1, target.calls.get())
        coVerify(exactly = 0) { cacheProvider.getCache(any()) }
        coVerify(exactly = 0) { cacheProvider.setCache(any(), any(), any()) }
    }

    @Test
    fun `non suspending functions are called directly without touching the cache`() {
        assertEquals("blocking-5", proxy.blocking(5))

        assertEquals(1, target.calls.get())
        coVerify(exactly = 0) { cacheProvider.getCache(any()) }
    }

    @Test
    fun `a slow cache lookup does not block the calling thread`() {
        val executor = Executors.newSingleThreadExecutor()
        val single = executor.asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { cacheProvider.getCache(key("value", 9)) } coAnswers {
            entered.complete(Unit)
            release.await()
            "cached-9"
        }

        try {
            val result = runBlocking {
                withTimeoutOrNull(10.seconds) {
                    val call = async(single) { proxy.value(9) }
                    launch(single) {
                        entered.await()
                        release.complete(Unit)
                    }
                    call.await()
                }
            }

            assertEquals("cached-9", result)
            assertEquals(0, target.calls.get())
        } finally {
            release.complete(Unit)
            single.close()
        }
    }
}
