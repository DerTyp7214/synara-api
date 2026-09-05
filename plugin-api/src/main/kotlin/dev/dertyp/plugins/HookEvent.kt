package dev.dertyp.plugins

import dev.dertyp.PlatformUUID

sealed interface HookEvent {
    data class ListenIngested(val listenBrainzUserId: PlatformUUID, val count: Int) : HookEvent
    data class PlaylistChanged(val playlistId: PlatformUUID) : HookEvent
    data class CollectionChanged(val collectionId: PlatformUUID) : HookEvent
    data class NowPlayingChanged(
        val userId: PlatformUUID,
        val songId: PlatformUUID?,
        val generation: Long,
        val startedAt: Long,
    ) : HookEvent
}
