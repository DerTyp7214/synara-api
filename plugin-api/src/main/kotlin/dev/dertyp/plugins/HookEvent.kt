package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.data.EntityType

sealed interface HookEvent {
    data class ListenIngested(val listenBrainzUserId: PlatformUUID, val count: Int) : HookEvent
    data class PlaylistChanged(val playlistId: PlatformUUID) : HookEvent
    data class CollectionChanged(val collectionId: PlatformUUID) : HookEvent
    data class NowPlayingChanged(
        val userId: PlatformUUID,
        val songId: PlatformUUID?,
        val generation: Long,
        val startedAt: Long,
        val positionMs: Long = 0,
        val playing: Boolean = true,
    ) : HookEvent
    data class EntitiesCreated(val type: EntityType, val ids: Set<PlatformUUID>) : HookEvent
    data class EntitiesUpdated(val type: EntityType, val ids: Set<PlatformUUID>) : HookEvent
    data class EntityMembersChanged(val type: EntityType, val ids: Set<PlatformUUID>) : HookEvent
    data class EntitiesDeleted(val type: EntityType, val ids: Set<PlatformUUID>) : HookEvent
    data class EntitiesMerged(
        val type: EntityType,
        val keptId: PlatformUUID,
        val removedIds: Set<PlatformUUID>,
    ) : HookEvent
    data class LikesChanged(val userId: PlatformUUID, val type: EntityType, val ids: Set<PlatformUUID>) : HookEvent
    data class TimecodesChanged(val userId: PlatformUUID, val songIds: Set<PlatformUUID>) : HookEvent
    data class AlbumsLinkedToMusicBrainz(val albumIds: Set<PlatformUUID>) : HookEvent
    data object LibraryIndexed : HookEvent
}
