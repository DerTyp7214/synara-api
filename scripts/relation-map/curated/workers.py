import re

from workerscan import scan_workers

S = "server/src/main/kotlin/dev/dertyp/"
SCHEDULE = S + "services/schedule/"

GROUPS = [
    {"id": "framework", "label": "Worker framework", "column": 0, "desc": "Discovery, scheduling, persistence and the ways a task gets started."},
    {"id": "maintenance", "label": "Maintenance", "column": 1, "desc": "Cleanup of sessions, queues, settings, logs and sizes."},
    {"id": "cleanup-chain", "label": "Library cleanup chain", "column": 1, "desc": "Runs from midnight, each step after the previous one succeeded."},
    {"id": "enrichment", "label": "Identification and enrichment", "column": 1, "desc": "MusicBrainz ids, mirrors, genres, images and provider links."},
    {"id": "releases", "label": "Releases", "column": 1},
    {"id": "audio", "label": "Audio", "column": 2, "desc": "Analysis, embeddings, transcodes and recommendation training."},
    {"id": "lyrics", "label": "Lyrics", "column": 2},
    {"id": "sync", "label": "Sync", "column": 2},
    {"id": "podcasts", "label": "Podcasts", "column": 2},
    {"id": "backup", "label": "Backup", "column": 2},
    {"id": "infrastructure", "label": "Infrastructure", "column": 2},
    {"id": "services", "label": "Services driven", "column": 3, "cols": 3, "desc": "The services each worker injects."},
    {"id": "targets", "label": "Systems touched", "column": 4, "cols": 2, "desc": "Datastores, sidecars, tools and third-party services a worker reaches through its services."},
]

STYLES = [
    {"kind": "after", "dash": "", "label": "runs after the other task succeeded"},
    {"kind": "drives", "dash": "dash", "label": "calls a service or reaches a system"},
    {"kind": "trigger", "dash": "dot", "label": "started by an event or by code, not by the schedule"},
]

TARGETS = {
    "database": ("Server database", "PostgreSQL or SQLite", "Rows are read and written through Exposed inside dbQuery."),
    "library-files": ("Library files", "music and image directories", "Audio files, image files and transcodes on the library volume."),
    "backup-dir": ("BACKUP_DIR", "local directory", "Backup zips, deduplicated image blobs and playlist JSON files. Nothing is sent to a remote target."),
    "proxy": ("proxy", "satellite process", "The reverse proxy the server dials out to."),
    "listen-backup": ("listen-backup", "satellite process", "Receiver that stores a copy of locally recorded listens."),
    "listenbrainz": ("ListenBrainz", "api.listenbrainz.org", "Listen history is pulled per linked account."),
    "musicbrainz": ("MusicBrainz", "musicbrainz.org/ws/2", "Queued at one request per second. Answers are mirrored into the mb_ tables."),
    "cover-art-archive": ("Cover Art Archive", "coverartarchive.org", "Front covers of releases and release groups."),
    "acoustid": ("AcoustID + fpcalc", "api.acoustid.org", "fpcalc produces the fingerprint, AcoustID answers with recordings."),
    "theaudiodb": ("TheAudioDB", "theaudiodb.com", "Genres, biographies and images for entities that have a MusicBrainz id."),
    "provider-apis": ("Provider APIs", "Tidal, Spotify, Deezer, Apple", "Lookups by ISRC, barcode or name on each configured metadata provider."),
    "linkresolver": ("LinkResolver", "linkresolver.synara.audio", "Resolves a URL, ISRC or UPC to links on other platforms. Needs LINKRESOLVER_API_KEY."),
    "apple-music": ("Apple Music catalog", "api.music.apple.com", "Catalog albums of followed artists. Needs the developer token."),
    "lrclib": ("LRCLIB", "lrclib.net", "Synced and plain lyrics by artist, title, album and duration."),
    "syncedlyrics": ("syncedlyrics", "command-line tool", "python3 -u -m syncedlyrics searches lyrics text by title and artist and writes an .lrc file."),
    "transcriber": ("transcriber", "WhisperX sidecar", "Aligns lyrics to the audio and returns word and character timings."),
    "audio-embed": ("audio-embed", "MusiCNN sidecar", "Returns one embedding vector per audio file path."),
    "recsys": ("recsys", "file exchange", "Trains from files on a shared volume and writes embeddings back. No network link."),
    "essentia": ("essentia extractor", "command-line tool", "essentia_streaming_extractor_music writes a JSON file of audio features."),
    "ffmpeg-inprocess": ("FFmpeg in process", "JavaCV", "Decoding, RMS envelopes and transcoding run inside the server JVM, not as a subprocess."),
    "ffmpeg-cli": ("ffprobe / ffmpeg", "command-line tools", "Stream facts through ffprobe and an audio MD5 through ffmpeg."),
    "metaflac": ("metaflac", "command-line tool", "Lists FLAC stream info and repairs seek points."),
    "podcast-hosts": ("Podcast hosts", "feeds and enclosures", "Feed URLs and episode enclosures on the publishers' hosts."),
    "image-origins": ("Image origin hosts", "stored origin URLs", "Images whose bytes are missing are downloaded again from the URL they came from."),
}

CURATED = {
    "reverse-proxy-health-check": {
        "group": "infrastructure",
        "does": "Starts ReverseProxyService when it is configured and not running. When it runs, the worker restarts the connection when it is not connected or when the last interaction is more than 30 seconds old. It is the only caller of ReverseProxyService.startService in the server sources.",
        "targets": [("proxy", "Tunnel start or restart", "WebSocket /proxy/server", "opens the tunnel that carries client kRPC frames", S + "services/ReverseProxyService.kt:122", "PROXY_HOSTNAME and PROXY_CONTROL_PORT")],
    },
    "database-backup": {
        "group": "backup",
        "does": "Writes a backup zip with the database dump, the file inventory and the image index, then rotates old zips.",
        "targets": [
            ("backup-dir", "Backup zip", "local files", "backup-<time>.zip plus deduplicated image blobs, keeps 10 zips", S + "services/BackupService.kt:156", "BACKUP_DIR"),
            ("database", "Database dump", "JDBC", "reads every table except search_index_queue into a zstd compressed CBOR dump", S + "services/DbManagementService.kt:111", None),
            ("library-files", "File inventory and images", "filesystem walk", "walks the audio directories for the file tree and copies image files into the blob store", S + "services/BackupService.kt:282", None),
        ],
    },
    "user-playlist-backup": {
        "group": "backup",
        "does": "Writes one JSON file per user with their playlists.",
        "targets": [("backup-dir", "Playlist JSON", "local files", "playlists-<userId>-<time>.json under user-playlists", S + "services/UserPlaylistBackupService.kt:46", "BACKUP_DIR")],
    },
    "session-cleanup": {
        "group": "maintenance",
        "does": "Removes sessions that are marked inactive or were last active more than 30 days ago.",
        "targets": [("database", "Old sessions", "JDBC", "deletes session rows that are inactive or older than 30 days", S + "services/SessionService.kt:66", None)],
    },
    "queue-cleanup": {
        "group": "maintenance",
        "does": "Clears play queues that were not modified for 30 days.",
        "targets": [("database", "Stale queues", "JDBC", "deletes the entries of stale queues and resets their userQueue row", S + "services/QueueService.kt:387", None)],
    },
    "client-settings-cleanup": {
        "group": "maintenance",
        "does": "Purges deleted client settings older than 30 days, trims the setting history to 20 entries and deletes devices not seen for 180 days.",
        "targets": [("database", "Client settings", "JDBC", "purges expired tombstones, old history entries and stale devices", S + "services/ClientSettingsService.kt:330", None)],
    },
    "entity-change-cleanup-worker": {
        "group": "maintenance",
        "does": "Removes entity change rows older than ENTITY_CHANGE_RETENTION_DAYS (30).",
        "targets": [("database", "Change rows", "JDBC", "deletes entity_change and user_entity_change rows past retention", S + "services/EntityChangeService.kt:248", "ENTITY_CHANGE_RETENTION_DAYS")],
    },
    "log-cleanup-worker": {
        "group": "maintenance",
        "does": "Deletes task log rows older than 30 days. It writes the table directly and injects no service.",
        "targets": [("database", "Task logs", "JDBC", "deletes scheduled_task_log rows older than 30 days", SCHEDULE + "LogCleanupWorker.kt:18", None)],
    },
    "radio-session-cleanup": {
        "group": "maintenance",
        "does": "Removes radio sessions that were not accessed for 24 hours. The sessions live in memory in RadioService, no datastore is touched.",
        "targets": [],
    },
    "storage-size-refresh": {
        "group": "maintenance",
        "does": "Recomputes the cached size of every storage category.",
        "targets": [("library-files", "Directory sizes", "filesystem walk", "recomputes the cached size of each storage category", S + "services/StorageService.kt:65", None)],
    },
    "merge-library-duplicates": {
        "group": "cleanup-chain",
        "does": "Merges duplicate songs, duplicate images and duplicate albums. Its default trigger is its own cron at 01:00, not a task of the chain.",
        "targets": [("database", "Merges", "JDBC", "rewrites references to the kept row and removes the duplicates", S + "services/LibraryMergeService.kt:23", None)],
    },
    "delete-empty-albums": {
        "group": "cleanup-chain",
        "does": "Deletes albums that have no songs left. It heads the cleanup chain.",
        "targets": [("database", "Empty albums", "JDBC", "deletes album rows without songs", S + "services/AlbumService.kt:2019", None)],
    },
    "delete-unreferenced-artists": {
        "group": "cleanup-chain",
        "does": "Deletes artists that no song, album or group membership references.",
        "targets": [("database", "Unreferenced artists", "JDBC", "deletes artist rows without references and their aliases", S + "services/ArtistService.kt:1262", None)],
    },
    "delete-unreferenced-images": {
        "group": "cleanup-chain",
        "does": "Deletes images that nothing references, rows and files.",
        "targets": [
            ("database", "Unreferenced images", "JDBC", "deletes image rows without references", S + "services/ImageService.kt:412", None),
            ("library-files", "Image files", "filesystem", "removes the image files of the deleted rows", S + "services/ImageService.kt:398", None),
        ],
    },
    "delete-unreferenced-animated-images": {
        "group": "cleanup-chain",
        "does": "Deletes animated images that nothing references, rows and files.",
        "targets": [
            ("database", "Unreferenced animated images", "JDBC", "deletes animated_image rows without references", S + "services/AnimatedImageService.kt:148", None),
            ("library-files", "Animated image files", "filesystem", "removes the files of the deleted rows", S + "services/AnimatedImageService.kt:169", None),
        ],
    },
    "image-analysis": {
        "group": "cleanup-chain",
        "does": "Analyses images that have no metadata yet, for at most 6 hours. When the bytes are missing it recovers them from the origin: a download for a URL, the embedded cover for an audio file, a new rendering for a generated cover. It also runs right after an index run.",
        "targets": [
            ("library-files", "Image analysis", "file read", "reads image bytes and stores the analysis in image_metadata", S + "services/ImageService.kt:477", None),
            ("image-origins", "Recover lost bytes", "HTTPS GET at LOW priority", "pulls the image again from its origin URL", S + "services/ImageService.kt:646", None),
        ],
    },
    "cover-backfill": {
        "group": "cleanup-chain",
        "does": "Generates covers for playlists and collections that have none. The rendering is local, from the asset packs.",
        "targets": [("library-files", "Generated covers", "local rendering", "renders a cover in the JVM from an asset pack and stores it as an image", S + "services/cover/CoverGenerationService.kt:125", "DATA_COVER_ASSETS_PATH")],
    },
    "audio-analysis": {
        "group": "audio",
        "does": "Analyses songs that have no audio data. The features come from the Essentia extractor, the timeline from in-process FFmpeg.",
        "targets": [
            ("essentia", "Feature extraction", "subprocess", "runs the extractor per song and reads its JSON into song_audio_data", S + "services/AudioAnalysisService.kt:105", "the binary on PATH"),
            ("ffmpeg-inprocess", "RMS timeline", "in-process decoding", "extracts the RMS envelope into song_audio_timeline", S + "services/audio/RmsEnvelopeExtractor.kt:27", None),
        ],
    },
    "audio-embedding": {
        "group": "audio",
        "does": "Sends batches of 16 file paths to audio-embed and stores the vectors. It marks the recommendation model dirty when anything was stored. It does nothing when AUDIO_EMBED_URL is not set.",
        "targets": [("audio-embed", "Embeddings", "HTTP POST /embed", "16 file paths per batch, returns one result per path, vectors go into song_audio_embedding", S + "services/AudioEmbeddingService.kt:54", "AUDIO_EMBED_URL")],
    },
    "audio-timeline-backfill": {
        "group": "audio",
        "does": "Runs analyzeSong for songs without a timeline and refreshEnvelopes for songs with an outdated one, within a budget of 6 hours.",
        "targets": [
            ("essentia", "Feature extraction", "subprocess", "analyzeSong runs the extractor again for songs without a timeline", SCHEDULE + "AudioTimelineBackfillWorker.kt:47", "the binary on PATH"),
            ("ffmpeg-inprocess", "Envelope refresh", "in-process decoding", "recomputes RMS envelopes for songs with a missing or outdated timeline", SCHEDULE + "AudioTimelineBackfillWorker.kt:67", None),
        ],
    },
    "flac-analysis": {
        "group": "audio",
        "does": "Reads FLAC stream info and repairs files without seek points.",
        "targets": [("metaflac", "FLAC info and seek points", "subprocess", "metaflac --list into flac_info, and --add-seekpoint=2s --add-padding=8192 as the fix", S + "services/FlacAnalysisService.kt:86", "the binary on PATH")],
    },
    "pcm-analysis": {
        "group": "audio",
        "does": "Reads stream facts and an audio MD5 for WAV and AIFF files.",
        "targets": [("ffmpeg-cli", "PCM facts", "subprocess", "ffprobe for stream facts and ffmpeg -f md5 for the audio hash, stored in pcm_info", S + "services/PcmAnalysisService.kt:79", "the binaries on PATH")],
    },
    "audio-start-analysis": {
        "group": "audio",
        "does": "Finds where the audio of a song actually starts. It also runs right after an index run.",
        "targets": [("ffmpeg-inprocess", "Start detection", "in-process decoding", "decodes the file with FFmpegFrameGrabber to find where the audio starts", S + "services/AudioStartAnalysisService.kt:63", None)],
    },
    "auto-transcoding": {
        "group": "audio",
        "does": "Transcodes songs ahead of time into every configured Opus and AAC quality.",
        "targets": [
            ("ffmpeg-inprocess", "Transcode", "in-process encoding", "encodes each configured Opus and AAC quality", S + "audio/Transcoder.kt:135", "the auto transcode qualities in the server config"),
            ("library-files", "Transcode files", "filesystem", "writes the result under AUDIO_TRANSCODE_PATH", S + "audio/Transcoder.kt:71", "AUDIO_TRANSCODE_PATH"),
        ],
    },
    "recommendation-training": {
        "group": "audio",
        "does": "Trains the recommendation model, only when it is dirty. A new listen batch, a playlist change or new audio embeddings make it dirty, and it starts dirty at boot.",
        "targets": [("recsys", "Training run", "files on a shared volume", "writes songs.jsonl, sequences.jsonl, meta.json and request.ready, waits for result.ready or result.failed, then reads embeddings.jsonl", S + "services/RecommendationService.kt:52", "RECSYS_DATA_DIR")],
    },
    "musicbrainz-worker": {
        "group": "enrichment",
        "does": "Assigns MusicBrainz ids to songs, albums and artists that have none, then merges duplicate albums. It also runs right after an index run.",
        "targets": [
            ("acoustid", "Fingerprint lookup", "fpcalc, then HTTPS GET /v2/lookup", "a song without an id is fingerprinted and looked up, accepted at a score of 0.85 or more", S + "services/SongService.kt:756", "ACOUSTID_API_KEY"),
            ("musicbrainz", "Id search", "HTTPS GET at LOW priority", "search by ISRC or by title and artist when no fingerprint match exists", S + "services/metadata/MusicBrainzService.kt:129", None),
        ],
    },
    "musicbrainz-cache-worker": {
        "group": "enrichment",
        "does": "Refetches mirrored MusicBrainz rows older than 90 days, mirrors the recordings that listens refer to and fetches covers for listened release groups.",
        "targets": [
            ("musicbrainz", "Mirror refresh", "HTTPS GET at LOW priority", "pulls artists, release groups, releases and recordings again into the mb_ tables", SCHEDULE + "MusicBrainzCacheWorker.kt:104", None),
            ("cover-art-archive", "Release group covers", "HTTPS GET", "front covers of listened release groups into mb_release_group_cover", S + "services/ReleaseService.kt:1536", None),
        ],
    },
    "genre-metadata-worker": {
        "group": "enrichment",
        "does": "Fetches genres for artists, albums and songs that have a MusicBrainz id.",
        "targets": [("theaudiodb", "Genres", "HTTPS GET artist-mb.php, album-mb.php, track-mb.php", "genres by MusicBrainz id, rechecked after 30 days", S + "services/MetadataFetchingService.kt:73", "the TheAudioDB key, default 123")],
    },
    "artist-image-worker": {
        "group": "enrichment",
        "does": "Fetches artist images from every provider that reports supported, one after the other in a fixed order: TheAudioDB, Tidal, Deezer, Apple Music, Spotify.",
        "targets": [
            ("theaudiodb", "Artist images", "HTTPS GET", "first provider in the order", S + "services/MetadataFetchingService.kt:37", None),
            ("provider-apis", "Artist images", "HTTPS GET", "Tidal, Deezer, Apple Music and Spotify, each only when it reports supported", S + "services/MetadataFetchingService.kt:373", "the credential of each provider"),
        ],
    },
    "fetch-metadata-theaudiodb": {
        "group": "enrichment",
        "does": "Fetches biographies and images from TheAudioDB.",
        "targets": [("theaudiodb", "Metadata", "HTTPS GET", "biographies and artist and album images by MusicBrainz id", S + "services/metadata/TheAudioDBService.kt:123", "the TheAudioDB key, default 123")],
    },
    "isrc-provider-enrichment-worker": {
        "group": "enrichment",
        "does": "Asks every metadata provider for the track by ISRC and the album by barcode and stores the provider URLs. It skips its run while RecentReleaseWorker is running.",
        "targets": [("provider-apis", "ISRC and barcode lookups", "HTTPS GET", "getTrackByIsrc and getAlbumByBarcode per provider, stored in song_provider and album_provider", SCHEDULE + "IsrcProviderEnrichmentWorker.kt:76", "the credential of each provider")],
    },
    "recent-release-worker": {
        "group": "releases",
        "does": "Builds the release feed for followed artists and then for the other library artists.",
        "targets": [
            ("musicbrainz", "Releases per artist", "HTTPS GET at LOW priority", "releases, release groups and recordings per artist", S + "services/ReleaseService.kt:1115", None),
            ("linkresolver", "Platform links", "HTTPS GET /resolve", "resolves the URL relations of a release group to platform links", S + "services/ReleaseService.kt:1297", "LINKRESOLVER_API_KEY"),
            ("provider-apis", "Album search", "HTTPS GET", "Apple album search when no Apple link exists, Tidal album search when no Tidal link exists", S + "services/ReleaseService.kt:1371", "provider credentials"),
            ("cover-art-archive", "Release covers", "HTTPS GET", "release-group front covers for releases that have followers", S + "services/ReleaseService.kt:1536", None),
        ],
    },
    "provider-enrichment-worker": {
        "group": "releases",
        "does": "Runs enrichProviders for albums, songs and singles. It skips or aborts while RecentReleaseWorker is running.",
        "targets": [
            ("linkresolver", "Cross-platform links", "HTTPS GET /resolve", "links for albums and songs into album_provider and song_provider", S + "services/AlbumService.kt:735", "LINKRESOLVER_API_KEY"),
            ("musicbrainz", "URL relations", "mirror first, HTTPS GET on a miss", "URL relations of the release or recording", S + "services/SongService.kt:1260", None),
        ],
    },
    "apple-music-release-worker": {
        "group": "releases",
        "does": "Fetches catalog releases of followed artists from Apple Music, 180 days back and 365 days ahead.",
        "targets": [
            ("apple-music", "Artist catalog albums", "HTTPS GET artists/{id}/albums", "catalog albums into provider_release", S + "services/release/AppleMusicReleaseService.kt:315", "the Apple Music developer token"),
            ("musicbrainz", "Release match", "HTTPS GET", "matches catalog albums by barcode and by URL", S + "services/release/AppleMusicReleaseService.kt:517", None),
            ("linkresolver", "Platform links", "HTTPS GET /resolve", "links for the catalog release", S + "services/release/AppleMusicReleaseService.kt:524", "LINKRESOLVER_API_KEY"),
        ],
    },
    "lyrics-sync-worker": {
        "group": "lyrics",
        "does": "Sends songs without a synced_lyrics row to the transcriber, one at a time, songs that have lyrics first. It waits up to 10 times 30 seconds for /health. A song without stored lyrics is first looked up with the syncedlyrics command-line tool.",
        "targets": [
            ("syncedlyrics", "Lyrics text lookup", "subprocess", "LyricsService.transcribeLyrics calls LyricsSearch.searchLyrics for a song without stored lyrics, the text goes to the transcriber", S + "services/LyricsService.kt:77", None),
            ("transcriber", "Lyrics alignment", "HTTP POST /transcribe", "file path, artist, title and lyrics, returns timings for synced_lyrics", S + "services/LyricsService.kt:86", "TRANSCRIBER_URL"),
        ],
    },
    "lrclib-worker": {
        "group": "lyrics",
        "does": "Fetches lyrics from LRCLIB for songs without lyrics whose last attempt is more than 7 days old, synced preferred over plain. It writes the song table directly and records the change through EntityEventPublisher.",
        "targets": [("lrclib", "Lyrics lookup", "HTTPS GET /api/get", "artist, track, album and duration, returns synced or plain lyrics", S + "services/LrcLibService.kt:13", None)],
    },
    "search-index-rebuild-worker": {
        "group": "infrastructure",
        "does": "Queues every song, album and artist for search indexing. It has no schedule and does nothing off PostgreSQL.",
        "targets": [("database", "Queue everything", "JDBC", "inserts all songs, albums and artists into search_index_queue", SCHEDULE + "SearchIndexRebuildWorker.kt:42", "PostgreSQL only")],
    },
    "listenbrainz-sync": {
        "group": "sync",
        "does": "Pulls new listens for every linked ListenBrainz account.",
        "targets": [("listenbrainz", "Listen history", "HTTPS GET /1/user/{name}/listens", "pulls up to 1000 listens per page back to the stored watermark", S + "services/sync/ListenBrainzService.kt:243", "a linked account, token optional")],
    },
    "listen-backup": {
        "group": "sync",
        "does": "Sends locally recorded listens newer than the stored cursor to the listen-backup receiver. It does nothing when the backup is disabled or has no URL.",
        "targets": [("listen-backup", "Listen batches", "HTTP POST /listens", "batches of LOCAL listens with the server id, 1000 per batch by default, cursor saved after each acknowledged batch", S + "services/sync/ListenBackupService.kt:319", "the listen_backup_config row")],
    },
    "podcast-refresh": {
        "group": "podcasts",
        "does": "Refreshes every subscribed feed with a conditional GET, scans the local podcast library, runs maintenance (queue imports, retention, purge) and triggers the import task when episodes were queued.",
        "targets": [
            ("podcast-hosts", "Feed refresh", "HTTPS GET with ETag and Last-Modified", "RSS feeds and feed artwork", S + "services/podcast/PodcastFeedService.kt:50", None),
            ("library-files", "Local podcast scan", "filesystem walk", "scans the local podcast library directory", S + "services/podcast/PodcastLocalScanService.kt:25", None),
        ],
    },
    "podcast-import": {
        "group": "podcasts",
        "does": "Downloads queued episodes, two at a time, and probes them.",
        "targets": [
            ("podcast-hosts", "Episode download", "HTTPS GET", "episode enclosures, two concurrent downloads", S + "services/podcast/PodcastImportService.kt:83", None),
            ("ffmpeg-inprocess", "Episode probe", "in-process decoding", "PodcastMediaProbe reads duration and embedded lyrics of the downloaded file", S + "services/podcast/PodcastMediaProbe.kt:57", None),
        ],
    },
}

EXTRA_EDGES = [
    ("isrc-provider-enrichment-worker", "recent-release-worker", "Skips while it runs", "in-process check", "returns at once, and skips the remaining items, while RecentReleaseWorker is running", SCHEDULE + "IsrcProviderEnrichmentWorker.kt:31"),
    ("provider-enrichment-worker", "recent-release-worker", "Skips or aborts while it runs", "in-process check", "returns at once, and aborts between its phases, while RecentReleaseWorker is running", SCHEDULE + "ProviderEnrichmentWorker.kt:22"),
]

SKIPPED_INJECTS = {"ApplicationEnvironment", "RecentReleaseWorker", "ScheduleService"}


def node_id(name):
    return re.sub(r"[^a-z0-9]+", "-", re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "-", name).lower()).strip("-")


def trigger_text(trigger):
    if trigger["kind"] == "cron":
        return "cron " + trigger["value"]
    if trigger["kind"] == "interval":
        return "every " + trigger["value"] + " s"
    if trigger["kind"] == "after":
        return "after " + trigger["value"]
    return "manual"


def short_trigger(trigger):
    text = trigger_text(trigger)
    return text if len(text) <= 30 else text[:29] + "\u2026"


def build(root):
    workers = scan_workers(root)
    scanned = {worker["key"] for worker in workers}
    missing = sorted(scanned - set(CURATED))
    stale = sorted(set(CURATED) - scanned)
    if missing or stale:
        raise SystemExit(
            "relation-map: curated/workers.py is out of date. "
            f"Workers without a curated entry: {missing or 'none'}. Curated keys without a worker: {stale or 'none'}."
        )
    by_key = {worker["key"]: worker for worker in workers}
    for worker in workers:
        after = worker["trigger"]["value"] if worker["trigger"]["kind"] == "after" else None
        if after and after not in by_key:
            raise SystemExit(f"relation-map: {worker['key']} runs after unknown task {after}")

    nodes = [
        {
            "id": "schedule-service", "label": "ScheduleService", "sub": "the scheduler", "group": "framework",
            "desc": "Keeps a priority queue of tasks ordered by their next run and reconciles it with the stored configuration. A worker runs through runExclusive, so an overlapping start is logged as skipped. A failed task that does not repeat is retried after 10 minutes. A successful run starts the tasks that are configured to run after it, also after a manual or post-index run.",
            "meta": [["Triggers", "cron, schedule, event, custom, task completion"], ["Manual", "a schedule at Instant.MAX"]],
            "src": SCHEDULE + "ScheduleService.kt:72",
        },
        {
            "id": "worker-scan", "label": "@WorkerTask scan", "sub": "ClassGraph", "group": "framework",
            "desc": "Every class annotated with @WorkerTask in dev.dertyp.services.schedule is found by a ClassGraph scan and bound in Koin through its no-argument constructor. Workers therefore take their dependencies with by inject.",
            "meta": [["Workers found", str(len(workers))], ["Declares", "key, name, enabled, at most one of cron, intervalSeconds, afterTask"]],
            "src": SCHEDULE + "WorkerTasks.kt:7",
        },
        {
            "id": "task-configuration", "label": "Task configuration", "sub": "scheduled_task_configuration", "group": "framework",
            "desc": "One row per task with its name, enabled flag and trigger. ensureDefaults inserts only the keys that are missing, an existing row keeps its stored trigger.",
            "meta": [["Admin API", "IScheduledTaskConfigurationService"]],
            "src": SCHEDULE + "ScheduledTaskConfigurationService.kt:48",
        },
        {
            "id": "task-log", "label": "Task log", "sub": "scheduled_task_log", "group": "framework",
            "desc": "A row per run: RUNNING with progress and the last five log lines, then SUCCESS with details or FAILURE with the message. Rows left in RUNNING are purged at startup, rows older than 30 days by the log cleanup task.",
            "meta": [["Admin API", "IScheduledTaskLogService"]],
            "src": S + "core/Task.kt:22",
        },
        {
            "id": "manual-trigger", "label": "triggerTask", "sub": "admin RPC", "group": "framework",
            "desc": "An admin can start any task by key. The call does not check whether the task is enabled.",
            "meta": [["Service", "IScheduledTaskConfigurationService.triggerTask(key)"]],
            "src": SCHEDULE + "ScheduleService.kt:321",
        },
        {
            "id": "library-indexed", "label": "LibraryIndexed", "sub": "event after an index run", "group": "framework",
            "desc": "BaseIndexer.afterIndex calls schedulePostIndexTasks, which publishes LibraryIndexed. ScheduleService answers by scheduling three workers for an immediate run.",
            "meta": [["Published by", "ScheduleService.schedulePostIndexTasks"]],
            "src": SCHEDULE + "ScheduleService.kt:255",
        },
        {
            "id": "search-index-worker", "label": "SearchIndexWorker", "sub": "continuous loop, not a task", "group": "infrastructure",
            "desc": "Not part of the worker framework. A loop that polls search_index_queue every 2 seconds, rebuilds search vectors in batches of 100 and writes Redis search documents when RediSearch is enabled. It is started only on PostgreSQL.",
            "meta": [["Started in", "configureServices"]],
            "src": S + "services/SearchIndexWorker.kt:21",
        },
    ]

    edges = {}

    def link(source, target, kind, label, flow):
        edge = edges.setdefault((source, target, kind), {"from": source, "to": target, "label": label, "kind": kind, "flows": []})
        edge["flows"].append(flow)

    used_targets = set()
    services = {}
    for worker in workers:
        curated = CURATED[worker["key"]]
        wid = node_id(worker["key"])
        label = worker["class"][:-6] if worker["class"].endswith("Worker") else worker["class"]
        node = {
            "id": wid, "label": label, "sub": short_trigger(worker["trigger"]), "group": curated["group"],
            "desc": curated["does"],
            "meta": [["Key", worker["key"]], ["Name", worker["name"]], ["Class", worker["class"] + " : " + worker["base"]], ["Default trigger", trigger_text(worker["trigger"])]],
            "src": worker["src"],
        }
        if not worker["enabled"]:
            node["flags"] = ["disabled-by-default"]
        nodes.append(node)
        if worker["trigger"]["kind"] == "after":
            link(node_id(worker["trigger"]["value"]), wid, "after", "then", {
                "title": "Task completion", "transport": "TaskCompletionTrigger",
                "carries": f"{worker['key']} starts when {worker['trigger']['value']} finished successfully",
                "trigger": "success of the earlier task, also after a manual or post-index run", "src": worker["src"],
            })
        for injected in worker["injects"]:
            if injected in SKIPPED_INJECTS:
                continue
            sid = "svc-" + node_id(injected)
            services.setdefault(sid, injected)
            link(wid, sid, "drives", "calls", {
                "title": injected, "transport": "by inject",
                "carries": f"{worker['class']} injects {injected} and calls it during a run", "src": worker["src"],
            })
        for target, title, transport, carries, src, config in curated["targets"]:
            if target not in TARGETS:
                raise SystemExit(f"relation-map: unknown target {target} for worker {worker['key']}")
            used_targets.add(target)
            flow = {"title": title, "transport": transport, "carries": carries, "trigger": trigger_text(worker["trigger"]), "src": src}
            if config:
                flow["config"] = config
            link(wid, "target-" + target, "drives", "reaches", flow)

    for sid in sorted(services):
        nodes.append({
            "id": sid, "label": services[sid], "group": "services",
            "desc": "Injected by the workers linked to it.", "meta": [],
        })
    for target in sorted(used_targets):
        label, sub, desc = TARGETS[target]
        nodes.append({"id": "target-" + target, "label": label, "sub": sub, "group": "targets", "desc": desc, "meta": []})

    for source, target, title, transport, carries, src in EXTRA_EDGES:
        link(node_id(source), node_id(target), "trigger", "yields to", {"title": title, "transport": transport, "carries": carries, "src": src})

    link("podcast-refresh", "podcast-import", "trigger", "triggerTask", {
        "title": "Import queued episodes", "transport": "ScheduleService.triggerTask(TaskKeys.PODCAST_IMPORT)",
        "carries": "starts the import task right away when the refresh queued episodes", "trigger": "queued > 0",
        "src": SCHEDULE + "PodcastRefreshWorker.kt:59",
    })
    link("audio-embedding", "recommendation-training", "trigger", "markDirty", {
        "title": "Model marked dirty", "transport": "RecommendationService.markDirty",
        "carries": "new audio embeddings make the next training run do work", "src": SCHEDULE + "AudioEmbeddingWorker.kt:31",
    })
    for key in ("musicbrainz-worker", "image-analysis", "audio-start-analysis"):
        link("library-indexed", node_id(key), "trigger", "post-index", {
            "title": "Post-index run", "transport": "HookEvent.LibraryIndexed",
            "carries": f"{by_key[key]['class']} runs immediately after an index run", "trigger": "BaseIndexer.afterIndex",
            "src": SCHEDULE + "ScheduleService.kt:252",
        })
    link("worker-scan", "schedule-service", "drives", "registers", {
        "title": "Managed workers", "transport": "registerManagedWorker(key, name, worker)",
        "carries": "every Worker bound in Koin is registered under its key", "trigger": "startup, configureScheduledTasks",
        "src": S + "core/ScheduledTasks.kt:21",
    })
    link("worker-scan", "task-configuration", "drives", "ensureDefaults", {
        "title": "Default configurations", "transport": "JDBC upsert of missing keys",
        "carries": "name, enabled flag and default trigger of every annotated worker, existing rows are left alone", "trigger": "startup",
        "src": SCHEDULE + "ScheduledTaskConfigurationService.kt:71",
    })
    link("schedule-service", "task-configuration", "drives", "reads", {
        "title": "Configuration flow", "transport": "configurationsFlow",
        "carries": "the scheduler collects configuration changes and reschedules through updateFromConfig", "src": SCHEDULE + "ScheduleService.kt:164",
    })
    link("schedule-service", "task-log", "drives", "logs", {
        "title": "Run log", "transport": "logTask",
        "carries": "a RUNNING row with progress, then SUCCESS with details or FAILURE with the message", "src": S + "core/Task.kt:22",
    })
    link("manual-trigger", "schedule-service", "trigger", "starts a task", {
        "title": "Manual run", "transport": "kRPC IScheduledTaskConfigurationService.triggerTask",
        "carries": "the task key, the enabled flag is not checked", "trigger": "admin action", "src": SCHEDULE + "ScheduleService.kt:321",
    })
    link("search-index-worker", "target-database", "drives", "reaches", {
        "title": "Search vector rebuild", "transport": "JDBC, 2 s idle poll",
        "carries": "reads search_index_queue in batches of 100, rebuilds search_vector and deletes the queue rows",
        "trigger": "continuous loop", "config": "PostgreSQL only", "src": S + "services/SearchIndexWorker.kt:79",
    })

    return {
        "id": "workers",
        "tab": "Workers",
        "noun": "task",
        "blurb": "Every scheduled task of the server, extracted from the @WorkerTask annotations, with the services it calls and the systems it reaches. Solid arrows are task chains: the task at the head runs after the one at the tail succeeded.",
        "out": "Starts, calls or reaches",
        "inn": "Started or called by",
        "layout": {"kind": "columns"},
        "groups": GROUPS,
        "styles": STYLES,
        "nodes": nodes,
        "edges": [edges[key] for key in sorted(edges)],
        "notes": [
            {"title": "Stored configuration wins over annotation defaults", "text": "ensureDefaults inserts only the keys that are missing from scheduled_task_configuration. For a key that already has a row, the stored trigger and enabled flag stay as they are.", "src": SCHEDULE + "ScheduledTaskConfigurationService.kt:71"},
            {"title": "Chains fire after manual runs too", "text": "A task configured to run after another one starts whenever that one succeeds, including a manual run and a post-index run. It does not start when the earlier task failed or was skipped as already running.", "src": SCHEDULE + "ScheduleService.kt:286"},
            {"title": "The proxy tunnel is started by the hourly check", "text": "ReverseProxyWorker is the only caller of ReverseProxyService.startService in the server sources. A cron trigger gets its first run at the next matching time, so the first start is at the next full hour.", "src": SCHEDULE + "ReverseProxyWorker.kt:23"},
            {"title": "The lyrics transcription task is off by default", "text": "LyricsSyncWorker is declared with enabled = false. LrcLibWorker at 04:30 is the lyrics source that runs by default.", "src": SCHEDULE + "LyricsSyncWorker.kt:18"},
            {"title": "Workers are created without arguments", "text": "The Koin binding calls the no-argument constructor of each scanned class, and the workers take their dependencies with by inject.", "src": SCHEDULE + "ScheduleModule.kt:20"},
            {"title": "This view fails the build when it is stale", "text": "The worker list is scanned from code. The descriptions and the systems each worker reaches are curated per task key in scripts/relation-map/curated/workers.py, and the generator stops when a scanned key has no entry or an entry has no worker."},
        ],
    }
