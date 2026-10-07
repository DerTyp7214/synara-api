package dev.dertyp.plugins

import dev.dertyp.data.EntityType

enum class HookGroup {
    LIBRARY,
    PLAYLISTS,
    USER_STATE,
    PLAYBACK,
}

fun HookEvent.hookGroup(): HookGroup? = when (this) {
    is HookEvent.EntitiesCreated -> type.hookGroup()
    is HookEvent.EntitiesUpdated -> type.hookGroup()
    is HookEvent.EntityMembersChanged -> type.hookGroup()
    is HookEvent.EntitiesDeleted -> type.hookGroup()
    is HookEvent.EntitiesMerged -> type.hookGroup()
    is HookEvent.AlbumsLinkedToMusicBrainz -> null
    HookEvent.LibraryIndexed -> HookGroup.LIBRARY
    is HookEvent.PlaylistChanged -> HookGroup.PLAYLISTS
    is HookEvent.CollectionChanged -> HookGroup.PLAYLISTS
    is HookEvent.LikesChanged -> HookGroup.USER_STATE
    is HookEvent.TimecodesChanged -> HookGroup.USER_STATE
    is HookEvent.NowPlayingChanged -> HookGroup.PLAYBACK
    is HookEvent.ListenIngested -> HookGroup.PLAYBACK
}

fun EntityType.hookGroup(): HookGroup? = when (this) {
    EntityType.SONG, EntityType.ALBUM, EntityType.ARTIST -> HookGroup.LIBRARY
    EntityType.USER_PLAYLIST, EntityType.PLAYLIST, EntityType.COLLECTION -> HookGroup.PLAYLISTS
    EntityType.UNKNOWN -> null
}
