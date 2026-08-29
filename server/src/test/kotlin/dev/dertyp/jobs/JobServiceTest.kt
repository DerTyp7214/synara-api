package dev.dertyp.jobs

import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.JobStatus
import dev.dertyp.services.jobs.JobService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class JobServiceTest {
    private val service = JobService()
    private val userId = UUID.randomUUID()
    private val user = UserInfo(userId, "u", null, false, emptyList())
    private val admin = UserInfo(UUID.randomUUID(), "a", null, true, emptyList())

    private suspend fun await(id: UUID, vararg statuses: JobStatus) = withTimeout(5.seconds) {
        while (service.get(id)!!.info.status !in statuses) yield()
    }

    @Test
    fun `same kind runs sequentially, different kinds in parallel`() = runBlocking {
        val gateA = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val a1 = service.enqueue("a", "a1", userId) { startedA.complete(Unit); gateA.await() }
        val a2 = service.enqueue("a", "a2", userId) { }
        val b1 = service.enqueue("b", "b1", userId) { }
        startedA.await()
        await(b1.id, JobStatus.SUCCEEDED)
        assertEquals(JobStatus.RUNNING, service.get(a1.id)!!.info.status)
        assertEquals(JobStatus.PENDING, service.get(a2.id)!!.info.status)
        gateA.complete(Unit)
        await(a2.id, JobStatus.SUCCEEDED)
        assertEquals(JobStatus.SUCCEEDED, service.get(a1.id)!!.info.status)
    }

    @Test
    fun `progress and log are exposed and failures are recorded`() = runBlocking {
        val job = service.enqueue("p", "p", userId) {
            log("one")
            progress(0.5, "half")
            log("two")
            throw IllegalStateException("kaput")
        }
        await(job.id, JobStatus.FAILED)
        assertEquals(listOf("one", "two"), service.logLines(job.id))
        assertEquals(0.5, job.info.progress)
        assertEquals("kaput", job.info.message)
        assertEquals(listOf("one", "two"), service.log(job.id).take(2).toList())
    }

    @Test
    fun `pending and running jobs can be cancelled, listing is filtered by user`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val running = service.enqueue("c", "running", userId) { started.complete(Unit); delay(60.seconds) }
        val pending = service.enqueue("c", "pending", UUID.randomUUID()) { }
        started.await()
        assertTrue(service.cancel(pending.id, admin))
        await(pending.id, JobStatus.CANCELLED)
        assertTrue(!service.cancel(running.id, UserInfo(UUID.randomUUID(), "x", null, false, emptyList())))
        assertTrue(service.cancel(running.id, user))
        await(running.id, JobStatus.CANCELLED)
        assertEquals(listOf("running"), service.snapshot("c", user).map { it.title })
        assertEquals(setOf("running", "pending"), service.snapshot("c", admin).map { it.title }.toSet())
        assertEquals(2, service.jobsFlow("c", admin).first().size)
    }
}
