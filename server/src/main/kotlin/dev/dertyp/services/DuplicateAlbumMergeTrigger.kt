package dev.dertyp.services

import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import kotlinx.coroutines.launch
import org.koin.core.component.inject

class DuplicateAlbumMergeTrigger : Service(), HookSubscriber {
    private val libraryMergeService by inject<LibraryMergeService>()

    override fun subscribe(hooks: HookService) {
        hooks.on<HookEvent.AlbumsLinkedToMusicBrainz> {
            scope.launch { libraryMergeService.mergeDuplicateAlbums() }
        }
    }
}
