package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.ReleaseService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.RECENT_RELEASE_WORKER, "Recent Release Worker")
class RecentReleaseWorker : Worker("RecentReleaseWorker") {
    private val releaseService by inject<ReleaseService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return releaseService.fetchNewReleases(onProgress)
    }
}
