package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AnimatedImageService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.DELETE_UNREFERENCED_ANIMATED_IMAGES, "Delete Unreferenced Animated Images")
class DeleteUnreferencedAnimatedImagesWorker : Worker("DeleteUnreferencedAnimatedImagesWorker") {
    private val animatedImageService by inject<AnimatedImageService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val count = animatedImageService.deleteUnreferencedAnimatedImages { p, l -> onProgress(p, l) }
        return mapOf("animatedImagesDeleted" to count)
    }
}
