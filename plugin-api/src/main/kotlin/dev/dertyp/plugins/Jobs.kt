package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserInfo
import kotlinx.coroutines.flow.Flow

enum class JobStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class JobInfo(
    val id: PlatformUUID,
    val kind: String,
    val source: String,
    val title: String,
    val summary: String,
    val user: PlatformUUID?,
    val status: JobStatus,
    val progress: Double?,
    val message: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
)

interface JobContext {
    val jobId: PlatformUUID
    val user: PlatformUUID?
    fun log(line: String)
    fun progress(value: Double?, message: String? = null)
    fun isActive(): Boolean
}

interface Jobs {
    suspend fun enqueue(kind: String, title: String, user: UserInfo?, summary: String = "", run: suspend JobContext.() -> Unit): PlatformUUID
    fun jobs(kind: String? = null, user: UserInfo? = null): Flow<List<JobInfo>>
    fun log(jobId: PlatformUUID): Flow<String>
    suspend fun cancel(jobId: PlatformUUID): Boolean
}
