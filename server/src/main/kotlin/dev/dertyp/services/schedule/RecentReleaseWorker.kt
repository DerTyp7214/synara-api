package dev.dertyp.services.schedule

import dev.dertyp.services.ReleaseService
import org.koin.core.component.inject

class RecentReleaseWorker : Worker("RecentReleaseWorker") {
    private val releaseService by inject<ReleaseService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        return releaseService.fetchNewReleases(onProgress)
    }
}
