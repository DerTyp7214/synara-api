package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.LibraryMergeService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.MERGE_LIBRARY_DUPLICATES, "Merge Library Duplicates")
class LibraryMergeWorker : Worker("LibraryMergeWorker") {
    private val libraryMergeService by inject<LibraryMergeService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return libraryMergeService.mergeDuplicates { p, l -> onProgress(p, l) }
    }
}
