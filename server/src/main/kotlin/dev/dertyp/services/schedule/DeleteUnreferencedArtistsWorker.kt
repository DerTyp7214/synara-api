package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.ArtistService
import org.koin.core.component.inject

@WorkerTask(
    TaskKeys.DELETE_UNREFERENCED_ARTISTS,
    "Delete Unreferenced Artists",
    afterTask = TaskKeys.DELETE_EMPTY_ALBUMS
)
class DeleteUnreferencedArtistsWorker : Worker("DeleteUnreferencedArtistsWorker") {
    private val artistService by inject<ArtistService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val count = artistService.deleteUnreferencedArtists { p, l -> onProgress(p, l) }
        return mapOf("artistsDeleted" to count)
    }
}
