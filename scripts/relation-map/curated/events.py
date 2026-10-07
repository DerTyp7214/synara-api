S = "server/src/main/kotlin/dev/dertyp/"
P = "plugin-api/src/main/kotlin/dev/dertyp/plugins/"

GROUPS = [
    {"id": "emitters", "label": "Emitters", "column": 0, "cols": 3, "desc": "Services that announce a write or a state change."},
    {"id": "bus", "label": "Publishing", "column": 1, "desc": "The publisher, the bus and the per-transaction buffer."},
    {"id": "clients", "label": "Client paths", "column": 1, "desc": "How a client learns about changes."},
    {"id": "entity-events", "label": "Entity events", "column": 2, "desc": "Buffered per transaction, coalesced, delivered after the outermost commit."},
    {"id": "other-events", "label": "Other events", "column": 2, "desc": "Events that do not describe an entity write."},
    {"id": "subscribers", "label": "Subscribers", "column": 3},
    {"id": "effects", "label": "Effects", "column": 4, "desc": "What a subscriber does or writes."},
]

STYLES = [
    {"kind": "in-transaction", "dash": "", "label": "inside the transaction of the write"},
    {"kind": "after-commit", "dash": "dash", "label": "after commit, or emitted directly"},
    {"kind": "plugin", "dash": "dot", "label": "through the plugin facade"},
]

ENTITY_EVENTS = [
    ("entities-created", "EntitiesCreated", "created", 12, "New entities of one type."),
    ("entities-updated", "EntitiesUpdated", "updated, relinked", 19, "Fields, links or containers of entities changed. relinked publishes this event too."),
    ("entity-members-changed", "EntityMembersChanged", "membersChanged", 39, "The members of a container changed: songs of a playlist, entries of a collection."),
    ("entities-deleted", "EntitiesDeleted", "deleting", 46, "Entities are being deleted. The in-transaction subscribers run before the rows are gone."),
    ("entities-merged", "EntitiesMerged", "merging", 53, "Several entities were merged into the kept one."),
    ("likes-changed", "LikesChanged", "likesChanged", 60, "A user liked or unliked songs, starred albums or followed artists."),
    ("timecodes-changed", "TimecodesChanged", "timecodesChanged", 67, "A user changed timecode tags of songs."),
    ("albums-linked", "AlbumsLinkedToMusicBrainz", "albumsLinkedToMusicBrainz", 73, "Albums got a MusicBrainz release. Server only: it has no hook group, so plugins never receive it."),
]

OTHER_EVENTS = [
    ("library-indexed", "LibraryIndexed", "An index run finished.", "LIBRARY"),
    ("playlist-changed", "PlaylistChanged", "A user playlist changed in a way that matters to its generated cover or to recommendations.", "PLAYLISTS"),
    ("collection-changed", "CollectionChanged", "A collection changed in a way that matters to its generated cover.", "PLAYLISTS"),
    ("now-playing-changed", "NowPlayingChanged", "A user started, paused or stopped playback.", "PLAYBACK"),
    ("listen-ingested", "ListenIngested", "A batch of listens was ingested from ListenBrainz.", "PLAYBACK"),
]

DECLARED_AT = {
    "ListenIngested": 7, "PlaylistChanged": 8, "CollectionChanged": 9, "NowPlayingChanged": 10, "EntitiesCreated": 18,
    "EntitiesUpdated": 19, "EntityMembersChanged": 20, "EntitiesDeleted": 21, "EntitiesMerged": 22, "LikesChanged": 27,
    "TimecodesChanged": 28, "AlbumsLinkedToMusicBrainz": 29, "LibraryIndexed": 30,
}

EVENT_GROUP = {
    "entities-created": "LIBRARY or PLAYLISTS, by entity type",
    "entities-updated": "LIBRARY or PLAYLISTS, by entity type",
    "entity-members-changed": "LIBRARY or PLAYLISTS, by entity type",
    "entities-deleted": "LIBRARY or PLAYLISTS, by entity type",
    "entities-merged": "LIBRARY or PLAYLISTS, by entity type",
    "likes-changed": "USER_STATE",
    "timecodes-changed": "USER_STATE",
    "albums-linked": "none",
}

EMITTERS = [
    ("album-service", "AlbumService", "services/AlbumService.kt", [
        ("created ALBUM", 1889), ("updated ALBUM", 696), ("deleting ALBUM", 2034), ("albumsLinkedToMusicBrainz", 521), ("recordChanges", 325),
    ]),
    ("artist-service", "ArtistService", "services/ArtistService.kt", [
        ("created ARTIST", 699), ("merging ARTIST", 700), ("deleting ARTIST", 843), ("updated ARTIST, SONG, ALBUM", 1034),
        ("leavingContainers SONG, ALBUM", 801), ("likesChanged ARTIST", 615), ("recordChanges", 250),
    ]),
    ("song-service", "SongService", "services/SongService.kt", [
        ("created SONG", 2725), ("updated SONG", 604), ("deleting ALBUM", 2821), ("likesChanged SONG", 504), ("recordChanges", 576),
    ]),
    ("library-merge-service", "LibraryMergeService", "services/LibraryMergeService.kt", [
        ("merging SONG", 128), ("merging ALBUM", 289), ("updated SONG, ALBUM", 151), ("likesChanged SONG", 670),
    ]),
    ("library-file-deleter", "LibraryFileDeleter", "services/LibraryFileDeleter.kt", [("deleting SONG", 48), ("deleting ALBUM", 67)]),
    ("playlist-service", "PlaylistService", "services/PlaylistService.kt", [
        ("created PLAYLIST", 318), ("membersChanged PLAYLIST", 334), ("deleting PLAYLIST", 121), ("recordChanges", 296),
    ]),
    ("user-playlist-service", "UserPlaylistService", "services/UserPlaylistService.kt", [
        ("created USER_PLAYLIST", 185), ("membersChanged USER_PLAYLIST", 205), ("deleting USER_PLAYLIST", 164), ("recordChanges", 253),
    ]),
    ("collection-service", "CollectionService", "services/CollectionService.kt", [
        ("created COLLECTION", 81), ("membersChanged COLLECTION", 169), ("deleting COLLECTION", 197), ("recordChanges", 113),
    ]),
    ("image-service", "ImageService", "services/ImageService.kt", [("updated, for the entities that hold the image", 584)]),
    ("audio-analysis-service", "AudioAnalysisService", "services/AudioAnalysisService.kt", [("updated SONG", 184)]),
    ("audio-start-analysis-service", "AudioStartAnalysisService", "services/AudioStartAnalysisService.kt", [("recordChanges", 56)]),
    ("lyrics-service", "LyricsService", "services/LyricsService.kt", [("updated SONG", 122)]),
    ("metadata-fetching-service", "MetadataFetchingService", "services/MetadataFetchingService.kt", [("recordChanges", 141)]),
    ("cover-generation-service", "CoverGenerationService", "services/cover/CoverGenerationService.kt", [("updated, for the playlist or collection", 178)]),
    ("release-service", "ReleaseService", "services/ReleaseService.kt", [("likesChanged ARTIST", 78)]),
    ("timecode-tag-service", "TimecodeTagService", "services/TimecodeTagService.kt", [("timecodesChanged", 95)]),
    ("tidal-base-importer", "TidalBaseImporter", "services/import/TidalBaseImporter.kt", [("recordChanges", 493)]),
    ("lrclib-worker", "LrcLibWorker", "services/schedule/LrcLibWorker.kt", [("recordChanges", 76)]),
    ("subsonic-query-service", "SubsonicQueryService", "services/subsonic/SubsonicQueryService.kt", [
        ("likesChanged ALBUM", 217), ("likesChanged ARTIST", 244), ("recordChanges", 268),
    ]),
]

DIRECT = [
    ("user-playlist-service", "playlist-changed", "PlaylistChanged", "services/UserPlaylistService.kt:188", "the playlist id. Five call sites, two of them only when no image id is set."),
    ("collection-service", "collection-changed", "CollectionChanged", "services/CollectionService.kt:83", "the collection id. Five call sites: the collection has no image, its image was cleared, or a member was added or removed."),
    ("listen-service", "listen-ingested", "ListenIngested", "services/ListenService.kt:90", "the ListenBrainz user id and the number of ingested listens"),
    ("scrobble-service", "now-playing-changed", "NowPlayingChanged", "services/ScrobbleService.kt:71", "user, song, generation, start time, position and playing flag, also with a null song when playback ends"),
]

SUBSCRIPTIONS = [
    ("entities-created", "version-group-trigger", "after-commit", "services/VersionGroupTrigger.kt:22", "only events of entity type ALBUM count, the ids are not read"),
    ("entities-updated", "version-group-trigger", "after-commit", "services/VersionGroupTrigger.kt:23", "only events of entity type ALBUM count, the ids are not read"),
    ("entities-deleted", "version-group-trigger", "after-commit", "services/VersionGroupTrigger.kt:20", "only events of entity type ALBUM count, the ids are not read"),
    ("entities-merged", "version-group-trigger", "after-commit", "services/VersionGroupTrigger.kt:21", "only events of entity type ALBUM count, the ids are not read"),
    ("albums-linked", "version-group-trigger", "after-commit", "services/VersionGroupTrigger.kt:24", "the event as a signal, the ids are not read"),
    ("albums-linked", "duplicate-album-merge-trigger", "after-commit", "services/DuplicateAlbumMergeTrigger.kt:12", "the event as a signal, the ids are not read"),
    ("entities-deleted", "search-index-remover", "after-commit", "services/SearchIndexRemover.kt:16", "song, album and artist ids"),
    ("entities-merged", "search-index-remover", "after-commit", "services/SearchIndexRemover.kt:17", "the removed song, album and artist ids"),
    ("library-indexed", "schedule-service", "after-commit", "services/schedule/ScheduleService.kt:252", "no payload"),
    ("now-playing-changed", "hue-service", "after-commit", "services/hue/HueService.kt:143", "the playback state of the user"),
    ("playlist-changed", "cover-auto-trigger", "after-commit", "services/cover/CoverAutoTrigger.kt:27", "the playlist id"),
    ("collection-changed", "cover-auto-trigger", "after-commit", "services/cover/CoverAutoTrigger.kt:28", "the collection id"),
    ("listen-ingested", "recommendation-plugin", "plugin", "services/recommendation/RecommendationPlugin.kt:21", "no payload is used"),
    ("playlist-changed", "recommendation-plugin", "plugin", "services/recommendation/RecommendationPlugin.kt:22", "no payload is used"),
]

NOTIFY = [
    ("listen-service", "LISTENS", "services/ListenService.kt:59"),
    ("scrobble-service", "LISTENS", "services/ScrobbleService.kt:47"),
    ("client-request-service", "ONLINE_DEVICES", "services/ClientRequestService.kt:138"),
    ("user-home-card-service", "HOME_CARDS", "services/ui/UserHomeCardService.kt:25"),
    ("listenbrainz-service", "LISTENBRAINZ_STATUS", "services/sync/ListenBrainzService.kt:36"),
]


def build(root):
    nodes = []
    edges = {}

    def link(source, target, kind, label, flow):
        edge = edges.setdefault((source, target, kind), {"from": source, "to": target, "label": label, "kind": kind, "flows": []})
        edge["flows"].append(flow)

    for node_id, label, path, verbs in EMITTERS:
        nodes.append({
            "id": node_id, "label": label, "group": "emitters",
            "desc": "Announces its writes through EntityEventPublisher, inside the transaction of the write.",
            "meta": [["Calls", ", ".join(verb for verb, _ in verbs)]],
            "src": S + path + ":" + str(verbs[0][1]),
        })
        for verb, line in verbs:
            carries = "the ids of the written entities"
            if verb == "recordChanges":
                carries = "compares the entity states read before and after the write and calls created, updated, relinked and membersChanged for what differs"
            link(node_id, "entity-event-publisher", "in-transaction", "announces", {
                "title": verb, "transport": "EntityEventPublisher, in process", "carries": carries,
                "trigger": "a write to a tracked table", "src": S + path + ":" + str(line),
            })

    extra_emitters = [
        ("listen-service", "ListenService", "Ingests listens. Emits ListenIngested and notifies the LISTENS topic.", "services/ListenService.kt:90"),
        ("scrobble-service", "ScrobbleService", "Tracks now-playing per user. Emits NowPlayingChanged and notifies the LISTENS topic.", "services/ScrobbleService.kt:71"),
        ("base-indexer", "BaseIndexer", "The indexer base class of the plugin API. It asks for the post-index tasks at the end of a run that indexed songs.", P + "BaseIndexer.kt:331"),
        ("client-request-service", "ClientRequestService", "Tracks which devices of a user are connected.", "services/ClientRequestService.kt:138"),
        ("user-home-card-service", "UserHomeCardService", "Stores the pinned home cards of a user.", "services/ui/UserHomeCardService.kt:25"),
        ("listenbrainz-service", "ListenBrainzService", "Syncs listens from ListenBrainz and reports the sync status.", "services/sync/ListenBrainzService.kt:36"),
    ]
    for node_id, label, desc, src in extra_emitters:
        nodes.append({"id": node_id, "label": label, "group": "emitters", "desc": desc, "meta": [], "src": src if src.startswith("plugin-api") else S + src})

    nodes += [
        {
            "id": "entity-event-publisher", "label": "EntityEventPublisher", "sub": "one call per write", "group": "bus",
            "desc": "The single place a service announces an entity change. Each verb first calls every in-transaction subscriber synchronously, then hands the typed event to HookService.publish. leavingContainers only reaches the in-transaction subscribers and publishes nothing. albumsLinkedToMusicBrainz only publishes.",
            "meta": [["Verbs", "created, updated, relinked, leavingContainers, membersChanged, deleting, merging, likesChanged, timecodesChanged, albumsLinkedToMusicBrainz"], ["Entity types", "SONG, ALBUM, ARTIST, USER_PLAYLIST, PLAYLIST, COLLECTION"]],
            "src": S + "services/EntityEventPublisher.kt:7",
        },
        {
            "id": "hook-service", "label": "HookService", "sub": "the event bus (HookBus)", "group": "bus",
            "desc": "publish attaches the event to the current Exposed transaction, or dispatches at once when there is none. emit delivers immediately and knows nothing about transactions. Every handler runs in its own coroutine, exceptions are logged.",
            "meta": [["Bound as", "HookBus"], ["Subscribers register in", "configureHooks, before DatabaseManager.init"]],
            "src": S + "services/HookService.kt:17",
        },
        {
            "id": "pending-hook-events", "label": "PendingHookEvents", "sub": "buffer per transaction", "group": "bus",
            "desc": "Collects the events of one transaction and delivers them after the outermost commit in a fixed order. One event per kind and entity type. Created then updated stays created. Created then deleted is dropped. Ids that were merged away are removed from deleted. A rollback clears the buffer.",
            "meta": [["Mechanism", "Exposed StatementInterceptor, afterCommit and afterRollback"]],
            "src": S + "services/PendingHookEvents.kt:19",
        },
    ]

    link("entity-event-publisher", "hook-service", "in-transaction", "publish", {
        "title": "Typed event", "transport": "HookService.publish", "carries": "one HookEvent per verb call, with the entity type and ids",
        "trigger": "every verb except leavingContainers", "src": S + "services/EntityEventPublisher.kt:12",
    })
    link("hook-service", "pending-hook-events", "in-transaction", "buffers", {
        "title": "Buffer until commit", "transport": "Exposed transaction interceptor",
        "carries": "the event is added to the buffer of the current transaction, or dispatched at once when no transaction is open",
        "src": S + "services/HookService.kt:65",
    })
    link("entity-event-publisher", "entity-change-recorder", "in-transaction", "records", {
        "title": "In-transaction subscriber", "transport": "EntityWriteSubscriber, called synchronously",
        "carries": "the verb with entity type and ids, so the change rows are written in the same transaction as the data",
        "trigger": "every verb except albumsLinkedToMusicBrainz, including leavingContainers", "src": S + "services/EntityEventPublisher.kt:11",
    })

    for node_id, label, verbs, line, desc in ENTITY_EVENTS:
        nodes.append({
            "id": node_id, "label": label, "group": "entity-events", "desc": desc,
            "meta": [["Publisher verb", verbs], ["Hook group", EVENT_GROUP[node_id]]], "src": P + "HookEvent.kt:" + str(DECLARED_AT[label]),
        })
        link("pending-hook-events", node_id, "after-commit", "delivers", {
            "title": label, "transport": "afterCommit, one coroutine per handler", "carries": "the coalesced ids of this kind and entity type",
            "trigger": "outermost commit of the transaction", "src": S + "services/EntityEventPublisher.kt:" + str(line),
        })
    for node_id, label, desc, group in OTHER_EVENTS:
        nodes.append({"id": node_id, "label": label, "group": "other-events", "desc": desc, "meta": [["Hook group", group]], "src": P + "HookEvent.kt:" + str(DECLARED_AT[label])})

    for source, target, label, src, carries in DIRECT:
        link(source, target, "after-commit", "emits", {
            "title": label, "transport": "HookService.emit, delivered immediately", "carries": carries, "src": S + src,
        })
    link("base-indexer", "library-indexed", "after-commit", "publishes", {
        "title": "LibraryIndexed", "transport": "IScheduleService.schedulePostIndexTasks, then HookService.publish",
        "carries": "no payload", "trigger": "end of an index run that indexed songs", "src": S + "services/schedule/ScheduleService.kt:255",
    })

    nodes += [
        {
            "id": "entity-change-recorder", "label": "EntityChangeRecorder", "sub": "in transaction", "group": "subscribers",
            "desc": "Writes the latest change per entity and aspect, the per-user rows for likes and timecodes, and the scope rows that tie a change to the artists, albums, playlists and collections it shows up in. A CommitStamp interceptor restamps changedAt in beforeCommit.",
            "meta": [["Registers with", "hooks.inTransaction(this)"], ["Guarded by", "EntityChangeCoverageTest"]],
            "src": S + "services/EntityChangeRecorder.kt:43",
        },
        {
            "id": "version-group-trigger", "label": "VersionGroupTrigger", "sub": "after commit, debounced", "group": "subscribers",
            "desc": "Waits for the quiet period without a further album event, at most the maximum wait after the first, then rebuilds the album version groups once.",
            "meta": [["Bound as", "HookSubscriber"], ["Quiet period", "versionGroups.rebuildQuietSeconds, VERSION_GROUP_REBUILD_QUIET_SECONDS, default 1"], ["Maximum wait", "versionGroups.rebuildMaxWaitSeconds, VERSION_GROUP_REBUILD_MAX_WAIT_SECONDS, default 5"]], "src": S + "services/VersionGroupTrigger.kt:12",
        },
        {
            "id": "duplicate-album-merge-trigger", "label": "DuplicateAlbumMergeTrigger", "sub": "after commit", "group": "subscribers",
            "desc": "Starts LibraryMergeService.mergeDuplicateAlbums when albums got their MusicBrainz release.",
            "meta": [["Bound as", "HookSubscriber"]], "src": S + "services/DuplicateAlbumMergeTrigger.kt:8",
        },
        {
            "id": "search-index-remover", "label": "SearchIndexRemover", "sub": "after commit", "group": "subscribers",
            "desc": "Removes deleted and merged-away songs, albums and artists from the Redis search. Other entity types are ignored.",
            "meta": [["Bound as", "HookSubscriber"]], "src": S + "services/SearchIndexRemover.kt:11",
        },
        {
            "id": "schedule-service", "label": "ScheduleService", "sub": "after commit", "group": "subscribers",
            "desc": "Runs the post-index workers when the library was indexed.",
            "meta": [["Bound as", "HookSubscriber"]], "src": S + "services/schedule/ScheduleService.kt:251",
        },
        {
            "id": "hue-service", "label": "HueService", "sub": "subscribes in startService", "group": "subscribers",
            "desc": "Syncs the lights to what is playing. It subscribes when the service starts, not through the HookSubscriber binding.",
            "meta": [], "src": S + "services/hue/HueService.kt:143",
        },
        {
            "id": "cover-auto-trigger", "label": "CoverAutoTrigger", "sub": "subscribes in startService", "group": "subscribers",
            "desc": "Schedules cover generation for the changed playlist or collection. It subscribes only when covers.autoGenerate is on, and not through the HookSubscriber binding.",
            "meta": [["Enabled by", "covers.autoGenerate, COVER_AUTO_GENERATE, default true"], ["Debounce", "covers.debounceSeconds, default 30"]], "src": S + "services/cover/CoverAutoTrigger.kt:25",
        },
        {
            "id": "recommendation-plugin", "label": "RecommendationPlugin", "sub": "built-in plugin", "group": "subscribers",
            "desc": "A built-in plugin that subscribes through its plugin context and marks the recommendation model dirty.",
            "meta": [], "src": S + "services/recommendation/RecommendationPlugin.kt:21",
        },
        {
            "id": "plugin-hooks", "label": "PluginHooks facade", "sub": "one per plugin", "group": "subscribers",
            "desc": "What a plugin gets as context.hooks. A plugin declares hook groups (LIBRARY, PLAYLISTS, USER_STATE, PLAYBACK) and may only subscribe to events of those groups. Each event is filtered by its group again on delivery. Handlers run on Dispatchers.IO. emit throws. Plugins below API version 4 get PLAYLISTS and PLAYBACK without declaring.",
            "meta": [["Created by", "HookService.forPlugin(id, groups)"], ["Removed", "when the plugin fails to load"]],
            "src": S + "services/PluginHooks.kt:16",
        },
    ]

    for source, target, kind, src, carries in SUBSCRIPTIONS:
        link(source, target, kind, "delivered to", {
            "title": "Subscription", "transport": "HookBus.on", "carries": carries, "src": S + src,
        })
    for node_id, label, _, _, _ in ENTITY_EVENTS:
        if node_id == "albums-linked":
            continue
        link(node_id, "plugin-hooks", "plugin", "to plugins", {
            "title": label, "transport": "PluginHooks.on, filtered by hook group",
            "carries": "delivered only to plugins that declared " + EVENT_GROUP[node_id], "src": P + "HookGroup.kt:12",
        })
    for node_id, label, _, group in OTHER_EVENTS:
        link(node_id, "plugin-hooks", "plugin", "to plugins", {
            "title": label, "transport": "PluginHooks.on, filtered by hook group",
            "carries": "delivered only to plugins that declared " + group, "src": P + "HookGroup.kt:12",
        })

    nodes += [
        {
            "id": "change-tables", "label": "Change tables", "sub": "entity_change and three more", "group": "effects",
            "desc": "entity_change holds the latest change per entity and aspect. user_entity_change holds the per-user rows. entity_change_scope ties a change to the containers it is visible in. entity_change_tracking is a single row with the time tracking started.",
            "meta": [["Retention", "ENTITY_CHANGE_RETENTION_DAYS, default 30"], ["Reset", "restartTracking after a database import"]],
            "src": S + "services/EntityChangeRecorder.kt:219",
        },
        {
            "id": "rebuild-version-groups", "label": "rebuildVersionGroups", "sub": "AlbumService", "group": "effects",
            "desc": "Recomputes which albums are editions of each other. One rebuild per burst of album events.",
            "meta": [], "src": S + "services/VersionGroupTrigger.kt:61",
        },
        {
            "id": "merge-duplicate-albums", "label": "mergeDuplicateAlbums", "sub": "LibraryMergeService", "group": "effects",
            "desc": "Runs the duplicate album merge over the library.",
            "meta": [], "src": S + "services/DuplicateAlbumMergeTrigger.kt:13",
        },
        {
            "id": "redis-search", "label": "Redis search documents", "sub": "RedisSearchService.remove", "group": "effects",
            "desc": "Deletes the search keys of removed songs, albums and artists. Nothing happens unless the Redis search is enabled.",
            "meta": [["Enabled by", "REDIS_HOST and REDIS_USE_SEARCH"]], "src": S + "services/SearchIndexRemover.kt:36",
        },
        {
            "id": "post-index-workers", "label": "Post-index workers", "sub": "three tasks", "group": "effects",
            "desc": "MusicBrainzWorker, ImageAnalysisWorker and AudioStartAnalysisWorker are scheduled to run immediately.",
            "meta": [], "src": S + "services/schedule/ScheduleService.kt:257",
        },
        {
            "id": "hue-lights", "label": "Hue light sync", "sub": "bridge on the local network", "group": "effects",
            "desc": "Combines the cover palette with the audio analysis and drives the lights, through the bridge client or the entertainment stream.",
            "meta": [], "src": S + "services/hue/HueService.kt:519",
        },
        {
            "id": "cover-generation", "label": "Cover generation", "sub": "CoverGenerationService", "group": "effects",
            "desc": "Generates a cover for the playlist or collection after a debounce. The new cover is announced as an update of that entity.",
            "meta": [], "src": S + "services/cover/CoverGenerationService.kt:125",
        },
        {
            "id": "recommendation-dirty", "label": "Recommendation model dirty", "sub": "markDirty", "group": "effects",
            "desc": "The next recommendation training task does work only when the model is dirty.",
            "meta": [], "src": S + "services/RecommendationService.kt:41",
        },
        {
            "id": "third-party-plugins", "label": "Third-party plugins", "sub": "jars in plugins/", "group": "effects",
            "desc": "Handlers of loaded plugins. They are never called inside the transaction of a write.",
            "meta": [], "src": S + "plugins/PluginManager.kt:108",
        },
        {
            "id": "entity-change-service", "label": "EntityChangeService", "sub": "what changed since a time", "group": "clients",
            "desc": "Serves the pulls of IEntityChangeService as finite flows read by keyset: all changes, or the changes visible with one artist, album, playlist or collection, merged with the caller's own per-user rows. getWindow tells the client how far back the server can answer.",
            "meta": [["RPC", "IEntityChangeService: getWindow, allChanges, byArtist, byAlbum, byPlaylist, byCollection"], ["Since", "API version 10"]],
            "src": S + "services/EntityChangeService.kt:100",
        },
        {
            "id": "change-notifier", "label": "ChangeNotifier", "sub": "separate push channel", "group": "clients",
            "desc": "Not part of the hook system. A per-user topic ping with 100 ms coalescing. The client reads the affected list again. Topics: ONLINE_DEVICES, HOME_CARDS, LISTENS, LISTENBRAINZ_STATUS.",
            "meta": [["RPC", "IChangeService.observeChanges"]], "src": S + "core/ChangeNotifier.kt:16",
        },
        {
            "id": "client-apps", "label": "Client apps", "sub": "kRPC flow or SSE", "group": "clients",
            "desc": "Pull library changes since their last sync and hold an open flow for topic pings.",
            "meta": [],
        },
    ]

    effects = [
        ("entity-change-recorder", "change-tables", "in-transaction", "upserts", "Change rows", "JDBC upsert in the transaction of the write", "latest change per entity and aspect, per-user rows, scope rows, stamped at commit", "services/EntityChangeRecorder.kt:233"),
        ("version-group-trigger", "rebuild-version-groups", "after-commit", "calls", "Version group rebuild", "AlbumService.rebuildVersionGroups", "one rebuild after the quiet period following the last album event, at most the maximum wait after the first", "services/VersionGroupTrigger.kt:61"),
        ("duplicate-album-merge-trigger", "merge-duplicate-albums", "after-commit", "calls", "Duplicate merge", "LibraryMergeService.mergeDuplicateAlbums", "a call without arguments, launched in the scope of the trigger", "services/DuplicateAlbumMergeTrigger.kt:13"),
        ("search-index-remover", "redis-search", "after-commit", "removes", "Search removal", "RedisSearchService.remove", "deletes the search keys of removed entities", "services/SearchIndexRemover.kt:36"),
        ("schedule-service", "post-index-workers", "after-commit", "runs", "Post-index run", "schedulePostIndexWorkers", "schedules the three workers with a trigger of now", "services/schedule/ScheduleService.kt:252"),
        ("hue-service", "hue-lights", "after-commit", "drives", "Light sync", "onNowPlaying", "palette, audio data and timeline scores become light commands", "services/hue/HueService.kt:519"),
        ("cover-auto-trigger", "cover-generation", "after-commit", "schedules", "Cover generation", "schedule(CoverTarget)", "the playlist or collection to render a cover for", "services/cover/CoverAutoTrigger.kt:27"),
        ("recommendation-plugin", "recommendation-dirty", "plugin", "marks", "Model dirty", "RecommendationService.markDirty", "sets the dirty flag", "services/recommendation/RecommendationPlugin.kt:21"),
        ("plugin-hooks", "third-party-plugins", "plugin", "delivers", "Plugin handlers", "handler on Dispatchers.IO", "events of the declared hook groups, never inside the transaction of a write", "services/PluginHooks.kt:67"),
        ("entity-change-service", "change-tables", "after-commit", "reads", "Change pull", "JDBC keyset read", "pulls rows changed since the given time", "services/EntityChangeService.kt:257"),
    ]
    for source, target, kind, label, title, transport, carries, src in effects:
        link(source, target, kind, label, {"title": title, "transport": transport, "carries": carries, "src": S + src})

    link("client-apps", "entity-change-service", "after-commit", "pulls", {
        "title": "Library changes since a time", "transport": "kRPC IEntityChangeService, finite flows",
        "carries": "pulls EntityChange rows since the client's last sync time", "trigger": "client sync",
        "config": "API version 10", "src": S + "services/RpcEntityChangeService.kt:15",
    })
    link("client-apps", "change-notifier", "after-commit", "observes", {
        "title": "Topic pings", "transport": "kRPC IChangeService.observeChanges, or SSE over REST",
        "carries": "pulls a flow of Change(topic) for the calling user, buffer 64, oldest dropped", "trigger": "while the client is connected",
        "src": S + "services/RpcChangeService.kt:12",
    })
    for source, topic, src in NOTIFY:
        link(source, "change-notifier", "after-commit", "notifies", {
            "title": topic, "transport": "ChangeNotifier.notify(userId, topic)", "carries": "a ping for the " + topic + " topic of one user", "src": S + src,
        })
    link("cover-generation", "entity-event-publisher", "in-transaction", "announces", {
        "title": "Generated cover", "transport": "EntityEventPublisher.updated",
        "carries": "the playlist or collection whose cover changed", "src": S + "services/cover/CoverGenerationService.kt:178",
    })

    return {
        "id": "events",
        "tab": "Events",
        "noun": "element",
        "blurb": "How a write becomes change rows, hook events and reactions. Read left to right: a service announces a write, the recorder stores it inside the same transaction, and the typed events reach the subscribers after the commit.",
        "out": "Leads to",
        "inn": "Comes from",
        "layout": {"kind": "columns"},
        "groups": GROUPS,
        "styles": STYLES,
        "nodes": nodes,
        "edges": [edges[key] for key in sorted(edges)],
        "notes": [
            {"title": "Two phases", "text": "The change rows are written inside the transaction of the write. Events handed to HookService.publish inside a transaction are buffered, delivered after its commit and dropped on rollback. Without a transaction publish dispatches at once.", "src": S + "services/HookService.kt:65"},
            {"title": "Coalescing", "text": "One event per kind and entity type per transaction. Created then updated stays created, created then deleted is dropped, merged ids are removed from deleted.", "src": S + "services/PendingHookEvents.kt:28"},
            {"title": "Two subscribers bypass the binding convention", "text": "HueService and CoverAutoTrigger subscribe in startService. VersionGroupTrigger, DuplicateAlbumMergeTrigger, SearchIndexRemover, EntityChangeRecorder and ScheduleService are bound as HookSubscriber and subscribed in configureHooks. HookSubscriberBindingTest checks that every HookSubscriber class is bound.", "src": S + "services/hue/HueService.kt:143"},
            {"title": "The indexer context can emit", "text": "The core PluginContext that Indexer builds hands out the raw HookBus, which can emit. The contexts built by PluginManager hand out a PluginHooks facade whose emit throws.", "src": S + "Indexer.kt:94"},
            {"title": "emit ignores transactions", "text": "PlaylistChanged, CollectionChanged, ListenIngested and NowPlayingChanged go through HookService.emit and are delivered immediately, also when the caller is inside a transaction that later rolls back.", "src": S + "services/HookService.kt:52"},
            {"title": "The push channel is separate", "text": "ChangeNotifier topics are in memory and per user. No hook handler calls ChangeNotifier.notify. Library deltas are pulled through IEntityChangeService instead.", "src": S + "core/ChangeNotifier.kt:21"},
        ],
    }
