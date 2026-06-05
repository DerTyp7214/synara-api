package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AlbumService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.DELETE_EMPTY_ALBUMS, "Delete Empty Albums")
class DeleteEmptyAlbumsWorker : Worker("DeleteEmptyAlbumsWorker") {
    private val albumService by inject<AlbumService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val count = albumService.deleteEmptyAlbums { p, l -> onProgress(p, l) }
        return mapOf("albumsDeleted" to count)
    }
}
