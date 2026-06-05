package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.ImageService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.DELETE_UNREFERENCED_IMAGES, "Delete Unreferenced Images")
class DeleteUnreferencedImagesWorker : Worker("DeleteUnreferencedImagesWorker") {
    private val imageService by inject<ImageService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val count = imageService.deleteUnreferencedImages { p, l -> onProgress(p, l) }
        return mapOf("imagesDeleted" to count)
    }
}
