package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.release.AppleMusicReleaseService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.APPLE_MUSIC_RELEASE_WORKER, "Apple Music Release Worker")
class AppleMusicReleaseWorker : Worker("AppleMusicReleaseWorker") {
    private val appleMusicReleaseService by inject<AppleMusicReleaseService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return appleMusicReleaseService.fetchFollowedArtistReleases(onProgress)
    }
}
