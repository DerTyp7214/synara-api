package dev.dertyp.services.jobs

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.JobContext
import dev.dertyp.plugins.JobInfo
import dev.dertyp.plugins.JobStatus
import dev.dertyp.plugins.Jobs
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JobService {
    private val logger = KtorSimpleLogger("JobService")

    inner class Job internal constructor(
        val id: UUID,
        val kind: String,
        val source: String,
        val payload: Any?,
        internal val run: suspend JobContext.() -> Unit,
        @Volatile var info: JobInfo,
    ) {
        internal val logLines = ArrayDeque<String>()
        internal var loggedLines = 0
        internal val logFlow =
            MutableSharedFlow<IndexedValue<String>>(
                extraBufferCapacity = 256,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )

        @Volatile
        internal var coroutine: kotlinx.coroutines.Job? = null

        @Volatile
        internal var cancelled = false
    }

    private inner class KindQueue {
        val channel = Channel<Job>(Channel.UNLIMITED)
        val pending = ArrayDeque<Job>()

        @Volatile
        var running: Job? = null
        val finished = ArrayDeque<Job>()
        val worker = ApplicationScope.scope.launch {
            for (job in channel) {
                pausedKinds.first { job.kind !in it }
                runJob(this@KindQueue, job)
            }
        }
    }

    private val pausedKinds = MutableStateFlow<Set<String>>(emptySet())

    fun pause(kind: String) {
        pausedKinds.value = pausedKinds.value + kind
    }

    fun resume(kind: String) {
        pausedKinds.value = pausedKinds.value - kind
    }

    private val queues = ConcurrentHashMap<String, KindQueue>()
    private val byId = ConcurrentHashMap<UUID, Job>()
    private val changeFlow =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val changes: Flow<Unit> = changeFlow.asSharedFlow()

    private fun queue(kind: String) = queues.getOrPut(kind) { KindQueue() }

    fun enqueue(
        kind: String,
        title: String,
        user: UUID?,
        summary: String = "",
        source: String = SERVER_SOURCE,
        payload: Any? = null,
        run: suspend JobContext.() -> Unit,
    ): Job {
        val id = UUID.randomUUID()
        val job = Job(
            id = id,
            kind = kind,
            source = source,
            payload = payload,
            run = run,
            info = JobInfo(
                id = id,
                kind = kind,
                source = source,
                title = title,
                summary = summary,
                user = user,
                status = JobStatus.PENDING,
                progress = null,
                message = null,
                createdAt = System.currentTimeMillis(),
                startedAt = null,
                finishedAt = null,
            ),
        )
        val queue = queue(kind)
        synchronized(queue) { queue.pending.addLast(job) }
        byId[id] = job
        queue.channel.trySend(job)
        changeFlow.tryEmit(Unit)
        return job
    }

    private suspend fun runJob(queue: KindQueue, job: Job) {
        val wasPending = synchronized(queue) { queue.pending.remove(job) }
        if (job.cancelled) {
            if (wasPending) finish(queue, job, JobStatus.CANCELLED, null)
            return
        }
        queue.running = job
        job.info = job.info.copy(status = JobStatus.RUNNING, startedAt = System.currentTimeMillis())
        changeFlow.tryEmit(Unit)
        val context = object : JobContext {
            override val jobId = job.id
            override val user = job.info.user
            override fun log(line: String) = this@JobService.log(job, line)
            override fun progress(value: Double?, message: String?) {
                job.info = job.info.copy(progress = value, message = message ?: job.info.message)
                changeFlow.tryEmit(Unit)
            }

            override fun isActive(): Boolean = !job.cancelled && job.coroutine?.isActive != false
        }
        var status = JobStatus.SUCCEEDED
        var message: String? = null
        try {
            coroutineScope {
                val coroutine = launch(start = CoroutineStart.LAZY) { job.run(context) }
                job.coroutine = coroutine
                if (job.cancelled) coroutine.cancel()
                coroutine.start()
                coroutine.join()
                if (coroutine.isCancelled) status = JobStatus.CANCELLED
            }
        } catch (e: CancellationException) {
            finish(queue, job, JobStatus.CANCELLED, null)
            throw e
        } catch (e: Exception) {
            logger.error("Job ${job.info.title} (${job.kind}) failed", e)
            status = JobStatus.FAILED
            message = e.message ?: e::class.simpleName
        }
        if (job.cancelled) status = JobStatus.CANCELLED
        finish(queue, job, status, message)
    }

    private fun finish(queue: KindQueue, job: Job, status: JobStatus, message: String?) {
        job.info = job.info.copy(
            status = status,
            message = message ?: job.info.message,
            finishedAt = System.currentTimeMillis()
        )
        synchronized(queue) {
            if (queue.running === job) queue.running = null
            queue.finished.addLast(job)
            while (queue.finished.size > FINISHED_RETENTION) byId.remove(queue.finished.removeFirst().id)
        }
        changeFlow.tryEmit(Unit)
    }

    private fun log(job: Job, line: String) {
        synchronized(job.logLines) {
            job.logLines.addLast(line)
            while (job.logLines.size > LOG_LINES) job.logLines.removeFirst()
            job.logFlow.tryEmit(IndexedValue(job.loggedLines++, line))
        }
    }

    fun get(jobId: UUID): Job? = byId[jobId]

    fun snapshot(kind: String? = null, user: UserInfo? = null): List<JobInfo> = jobsOf(kind, user).map { it.info }

    fun jobsOf(kind: String? = null, user: UserInfo? = null): List<Job> {
        val selected = if (kind == null) queues.values.toList() else listOfNotNull(queues[kind])
        return selected.flatMap { queue ->
            synchronized(queue) { listOfNotNull(queue.running) + queue.pending.toList() + queue.finished.toList() }
        }.filter { user == null || user.isAdmin || it.info.user == user.id }
    }

    fun jobsFlow(kind: String? = null, user: UserInfo? = null): Flow<List<JobInfo>> =
        changeFlow.onSubscription { emit(Unit) }.map { snapshot(kind, user) }

    fun log(jobId: UUID): Flow<String> = flow {
        val job = byId[jobId] ?: return@flow
        var emitted = -1
        emitAll(
            job.logFlow
                .onSubscription {
                    val (logged, buffered) = synchronized(job.logLines) { job.loggedLines to job.logLines.toList() }
                    buffered.forEachIndexed { index, line -> emit(IndexedValue(logged - buffered.size + index, line)) }
                }
                .filter { it.index > emitted }
                .map {
                    emitted = it.index
                    it.value
                }
        )
    }

    fun logLines(jobId: UUID): List<String> =
        byId[jobId]?.let { job -> synchronized(job.logLines) { job.logLines.toList() } } ?: emptyList()

    fun cancel(jobId: UUID, user: UserInfo? = null): Boolean {
        val job = byId[jobId] ?: return false
        if (user != null && !user.isAdmin && job.info.user != user.id) return false
        if (job.info.status != JobStatus.PENDING && job.info.status != JobStatus.RUNNING) return false
        job.cancelled = true
        job.coroutine?.cancel()
        if (job.info.status == JobStatus.PENDING) {
            val queue = queue(job.kind)
            val removed = synchronized(queue) { queue.pending.remove(job) }
            if (removed) finish(queue, job, JobStatus.CANCELLED, null)
        }
        changeFlow.tryEmit(Unit)
        return true
    }

    fun forSource(source: String): Jobs = object : Jobs {
        override suspend fun enqueue(
            kind: String,
            title: String,
            user: UserInfo?,
            summary: String,
            run: suspend JobContext.() -> Unit
        ): UUID =
            this@JobService.enqueue(
                kind = kind,
                title = title,
                user = user?.id,
                summary = summary,
                source = source,
                payload = null,
                run = run,
            ).id

        override fun jobs(kind: String?, user: UserInfo?): Flow<List<JobInfo>> = jobsFlow(kind, user)
        override fun log(jobId: UUID): Flow<String> = this@JobService.log(jobId)
        override suspend fun cancel(jobId: UUID): Boolean =
            byId[jobId]?.takeIf { it.source == source }?.let { cancel(it.id) } ?: false
    }

    companion object {
        const val SERVER_SOURCE = "server"
        const val FINISHED_RETENTION = 100
        const val LOG_LINES = 500
    }
}
