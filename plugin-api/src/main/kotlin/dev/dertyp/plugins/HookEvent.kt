package dev.dertyp.plugins

import dev.dertyp.PlatformUUID

sealed interface HookEvent {
    data class ListenIngested(val listenBrainzUserId: PlatformUUID, val count: Int) : HookEvent
    data class PlaylistChanged(val playlistId: PlatformUUID) : HookEvent
}
