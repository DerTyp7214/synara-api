S = "server/src/main/kotlin/dev/dertyp/"
W = S + "services/schedule/"
PA = "plugin-api/src/main/kotlin/dev/dertyp/plugins/"

GROUPS = [
    {"id": "import", "label": "1. Import", "desc": "From a pasted URL, ISRC or UPC to rows in the library."},
    {"id": "enrich", "label": "2. Identification and enrichment", "desc": "Nightly chain that identifies songs and fills in metadata. Each worker starts after the one before it succeeds."},
    {"id": "audio", "label": "3. Audio and recommendations", "desc": "Audio features, embeddings, transcodes and the recommendation model."},
    {"id": "lyrics", "label": "4. Lyrics", "desc": "Fetched lyrics and word-level timing."},
    {"id": "releases", "label": "5. Releases", "desc": "The release feed of followed artists and the links to other platforms."},
    {"id": "podcasts", "label": "6. Podcasts", "desc": "Feeds, episode imports, streaming and transcripts."},
    {"id": "hue", "label": "7. Hue", "desc": "Lights that follow what is playing."},
    {"id": "listening", "label": "8. Listening", "desc": "Listens coming in and going out."},
    {"id": "search", "label": "9. Search", "desc": "How the search index is kept and queried on each database."},
    {"id": "backup", "label": "10. Backup and restore", "desc": "Database backups and the ways a server is set up from existing data."},
]

STYLES = [
    {"kind": "step", "dash": "", "label": "next step"},
    {"kind": "external", "dash": "dash", "label": "call to an external service, tool or sidecar"},
    {"kind": "trigger", "dash": "dot", "label": "started by a schedule, an event or a finished task"},
]

NODES = []
EDGES = []
NOTES = []


def node(id, label, group, desc, sub=None, meta=None, src=None, flags=None):
    n = {"id": id, "label": label, "group": group, "desc": desc, "meta": meta or []}
    if sub:
        n["sub"] = sub
    if src:
        n["src"] = src
    if flags:
        n["flags"] = flags
    NODES.append(n)


def flow(title, transport, carries, src, trigger=None, config=None):
    f = {"title": title, "transport": transport, "carries": carries, "src": src}
    if trigger:
        f["trigger"] = trigger
    if config:
        f["config"] = config
    return f


def edge(a, b, label, kind, *flows):
    EDGES.append({"from": a, "to": b, "label": label, "kind": kind, "flows": list(flows)})


def note(title, text, src, flags=None):
    n = {"title": title, "text": text, "src": src}
    if flags:
        n["flags"] = flags
    NOTES.append(n)


G = "import"
node("im-entry", "Import request", G, "IImportService.importUrls and importIds from a client, or the server-driven UI intake where ImporterResolvers offers one resolver per importer.", "RPC or UI intake", None, S + "services/import/ImportService.kt:93")
node("im-classify", "MusicCode.classify", G, "Each input is classified as a URL, an ISRC or a UPC. Codes are resolved to platform URLs through LinkResolver.", "URL, ISRC or UPC", None, S + "services/import/ImportService.kt:98")
node("im-resolve", "ImporterProxy", G, "resolveImporter picks the importer. It tries the URL itself, then sibling URLs of the same release from the MusicBrainz relation mirror, then LinkResolver. The preferred importer wins among the candidates, tiddl by default.", "resolveImporter",
     [["Order", "canHandle, MBRelationProviderTable siblings, LinkResolver"]], S + "services/import/ImporterProxy.kt:41")
node("im-linkresolver", "LinkResolver", G, "linkresolver.synara.audio turns a URL, ISRC or UPC into links on other platforms. Disabled without a key.", "external", None, S + "services/metadata/LinkResolverService.kt:24")
node("im-upcoming", "Upcoming release import", G, "UpcomingReleaseImportService handles an Apple or MusicBrainz release that is not out yet. It takes the tracklist, looks each ISRC up on Tidal and queues https://tidal.com/track/{id}.", "optional detour", None, S + "services/import/UpcomingReleaseImportService.kt:87")
node("im-queue", "Import queue", G, "ImportService.addToQueue hands entries to JobService, which runs one job per kind at a time. runEntry calls importerProxy.importContent.", "JobService", None, S + "services/import/ImportService.kt:356")
node("im-premeta", "Pre-download metadata", G, "TidalBaseImporter.importContent collects, before any download: Tidal track and album metadata, a MusicBrainz release by barcode or title or a recording search, a cover and the animated cover.", "TidalBaseImporter",
     [["Cover order", "provided, Cover Art Archive, Tidal"]], S + "services/import/TidalBaseImporter.kt:129")
node("im-tidal", "Tidal API", G, "openapi.tidal.com/v2 for tracks, albums, cover art and playlists, with a linked user's token when there is one, else the app token.", "external", None, S + "services/metadata/TidalService.kt:109")
node("im-mb", "MusicBrainz", G, "musicbrainz.org/ws/2, read through the mb_* mirror tables and paced at one request per second.", "external", None, S + "services/metadata/MusicBrainzService.kt:63")
node("im-caa", "Cover Art Archive", G, "coverartarchive.org release and release-group fronts.", "external", None, S + "services/import/TidalBaseImporter.kt:316")
node("im-materialize", "Credential files", G, "ImporterCredentialMaterializer.withFiles wraps the command of an importer that has a credential name. When the credential is managed by the credential server it takes a per-name lock, fetches the auth files, writes them with mode 0600, runs the tool, and writes changed files back. Otherwise the tool runs directly.", "ImporterCredentialMaterializer", None, S + "services/credentials/ImporterCredentialMaterializer.kt:21")
node("im-tiddl", "tiddl", G, "tiddl download url <urls>, installed from a patched fork in both Dockerfiles. Auth in ~/.tiddl/auth.json.", "CLI tool", None, S + "services/import/TiddlService.kt:23")
node("im-tdn", "tidal-dl-ng", G, "tdn dl <urls>. Token in ~/.config/tidal_dl_ng/token.json. Installed by Dockerfile, not by Dockerfile.nobuild.", "CLI tool", None, S + "services/import/TdnService.kt:35")
node("im-gamdl", "gamdl + ffmpeg", G, "gamdl with cookies and an optional .wvd file, then ffmpeg turns each new .m4a into the configured lossless format. GamdlService extends BaseImporter and has its own importContent: it runs the tool through executeImporter, finds the .m4a files modified since the start, transcodes them and calls indexer.queue itself. It does not call collectImportedFiles and has no metadata or tagging step, ffmpeg copies the tags of the download with -map_metadata 0. No gamdl install appears in either Dockerfile.", "CLI tools", None, S + "services/gamdl/GamdlService.kt:77")
node("im-ytdlp", "yt-dlp", G, "YouTube and SoundCloud imports. Audio is extracted to the configured lossless format with thumbnail and subtitles as LRC. These importers declare no credential name.", "CLI tool", None, S + "services/youtube/YoutubeService.kt:58")
node("im-collect", "Collect files", G, "collectImportedFiles runs the tool, finds the files modified since the start, converts Atmos .m4a to lossless while keeping the Atmos sibling, and parses .m3u playlists.", "BaseImporter", None, S + "services/import/BaseImporter.kt:81")
node("im-tag", "Tagging", G, "jaudiotagger rewrites title, artist, album, MusicBrainz ids, lyrics and cover in the files.", "jaudiotagger", None, S + "services/import/TidalBaseImporter.kt:388")
node("im-index", "Indexer", G, "indexer.queue hands the files to the indexer of the source: TidalIndexer, GamdlIndexer, YoutubeIndexer or SoundcloudIndexer, all BaseIndexer. Indexer.queue routes a call with another type to the same indexers. BaseIndexer.start groups by album and writes album metadata, images, songs and playlists.", "Indexer, BaseIndexer", None, S + "Indexer.kt:160")
node("im-tables", "Library tables", G, "song, album, artist, image and playlist rows with their link tables. Animated covers are stored and linked afterwards.", "song, album, artist, image", None, PA + "BaseIndexer.kt:253")
node("im-indexed", "LibraryIndexed", G, "BaseIndexer.afterIndex calls scheduleService.schedulePostIndexTasks when the run indexed at least one song, which publishes the LibraryIndexed event.", "event", None, W + "ScheduleService.kt:255")
node("im-post", "Post-index workers", G, "ScheduleService runs MusicBrainzWorker, ImageAnalysisWorker and AudioStartAnalysisWorker right away, and their after-task chains follow.", "3 workers", None, W + "ScheduleService.kt:257")

edge("im-entry", "im-classify", "inputs", "step", flow("Classify inputs", "MusicCode.classify per input", "a URL, an ISRC or a UPC", S + "services/import/ImportService.kt:98", "user import"))
edge("im-classify", "im-resolve", "URL or code", "step",
     flow("Pick the importer", "ImporterProxy.resolveImporter or resolveImporterByCode", "the input and the preferred importer", S + "services/import/ImporterProxy.kt:41", "per input"))
edge("im-resolve", "im-linkresolver", "resolve", "external",
     flow("Platform links", "HTTPS GET /resolve?url|isrc|upc with X-API-Key", "links on other platforms for the same track or album", S + "services/import/ImporterProxy.kt:50", "no importer handles the input directly", "LINKRESOLVER_API_KEY"))
edge("im-resolve", "im-mb", "siblings", "step",
     flow("Sibling URLs", "MusicBrainzCacheService.relationSiblingUrls on MBRelationProviderTable", "other platform URLs of the same release", S + "services/metadata/MusicBrainzCacheService.kt:569", "the URL itself has no importer, before LinkResolver"))
edge("im-resolve", "im-upcoming", "not released yet", "step",
     flow("Upcoming release", "UpcomingReleaseImportService", "an Apple or MusicBrainz release id", S + "services/import/UpcomingReleaseImportService.kt:87", "intake of an upcoming release"))
edge("im-upcoming", "im-tidal", "ISRC lookup", "external",
     flow("Find the tracks on Tidal", "HTTPS GET /tracks by ISRC", "Tidal track ids for the tracklist from the Apple catalog and MusicBrainz", S + "services/import/UpcomingReleaseImportService.kt:309", "per track", "Apple developer token and Tidal credentials"))
edge("im-upcoming", "im-queue", "track URLs", "step", flow("Queue the tracks", "ImportService.addToQueue", "https://tidal.com/track/{id} per track", S + "services/import/UpcomingReleaseImportService.kt:191"))
edge("im-resolve", "im-queue", "entry", "step", flow("Queue the import", "ImportService.addToQueue to JobService", "an ImportQueueEntry", S + "services/import/ImportService.kt:356"))
edge("im-queue", "im-premeta", "runEntry", "step", flow("Run the job", "runEntry calls importerProxy.importContent, which reaches TidalBaseImporter.importContent for tiddl and tdn", "the entry", S + "services/import/ImportService.kt:495", "job reaches the front of its queue"))
edge("im-queue", "im-materialize", "gamdl job", "step",
     flow("Run a gamdl job", "importerProxy.importContent calls GamdlService.importContent, which starts the tool through executeImporter", "Apple Music URLs with the gamdl cookies and the .wvd file when one is configured. No metadata is collected before the download", S + "services/gamdl/GamdlService.kt:258", "job reaches the front of its queue, Apple Music URL"))
edge("im-premeta", "im-tidal", "metadata", "external",
     flow("Track and album metadata", "HTTPS GET /tracks/{id}, /albums/{id} at HIGH priority", "titles, artists, barcode, cover URLs", S + "services/import/TidalBaseImporter.kt:158", "import job", "tidal.api credential or a linked user token"))
edge("im-premeta", "im-mb", "release match", "external",
     flow("Canonical tags", "HTTPS GET /release by barcode or title, or /recording search", "MusicBrainz ids and canonical names", S + "services/import/TidalBaseImporter.kt:190", "import job"))
edge("im-premeta", "im-caa", "cover", "external",
     flow("Cover", "HTTPS GET /release/{id}/front", "the cover image, used when none was provided", S + "services/import/TidalBaseImporter.kt:316", "import job"))
edge("im-premeta", "im-materialize", "before the tool", "step", flow("Prepare credentials", "ImporterCredentialMaterializer", "the credential files of the importer, such as auth.json and token.json", S + "services/import/BaseImporter.kt:338", "importer command with a credential name"))
edge("im-materialize", "im-tiddl", "run", "external", flow("tiddl download", "python3 -u tiddl download url <urls>", "audio files into the tiddl directory", S + "services/import/TiddlService.kt:23", "Tidal URL, tiddl selected"))
edge("im-materialize", "im-tdn", "run", "external", flow("tdn download", "python3 -u tdn dl <urls>", "audio files and .m3u playlists", S + "services/import/TdnService.kt:35", "Tidal URL, tdn selected"))
edge("im-materialize", "im-gamdl", "run", "external",
     flow("gamdl download", "gamdl --no-config-file --cookies-path ... <urls>", "Apple Music .m4a files", S + "services/gamdl/GamdlService.kt:77", "Apple Music URL"))
edge("im-gamdl", "im-index", "indexer.queue", "step",
     flow("Transcode to FLAC", "ffmpeg -y -i in.m4a -map 0:a -map 0:v? -c:a flac -c:v copy -map_metadata 0 out", "a lossless file per .m4a file modified since the start, FLAC arguments shown", S + "services/gamdl/GamdlService.kt:304", "after the gamdl run, per new .m4a file"),
     flow("Index the files", "GamdlService.importContent calls indexer.queue", "the transcoded files with the importer id, without collectImportedFiles and without tagging", S + "services/gamdl/GamdlService.kt:276", "after the transcodes"))
edge("im-queue", "im-ytdlp", "run", "external",
     flow("yt-dlp download", "yt-dlp -x --audio-format <flac|wav|aiff> --write-subs --convert-subs lrc ...", "audio, thumbnail and subtitles", S + "services/youtube/YoutubeService.kt:59", "YouTube or SoundCloud URL", "optional YTDLP_CONFIG_PATH"))
for tool in ("im-tiddl", "im-tdn", "im-ytdlp"):
    edge(tool, "im-collect", "new files", "step", flow("Collect the output", "files modified since the tool started", "downloaded audio files", S + "services/import/BaseImporter.kt:81"))
edge("im-collect", "im-tag", "files", "step", flow("Write tags", "jaudiotagger", "the metadata gathered before the download", S + "services/import/TidalBaseImporter.kt:388"))
edge("im-tag", "im-index", "indexer.queue", "step", flow("Index the files", "indexer.queue", "file paths with the importer id", S + "services/import/BaseImporter.kt:191"))
edge("im-index", "im-tables", "writes", "step", flow("Write the library", "BaseIndexer.start", "albums, images, songs and playlists", PA + "BaseIndexer.kt:253"))
edge("im-index", "im-indexed", "publishes", "trigger", flow("Announce the index run", "schedulePostIndexTasks publishes LibraryIndexed", "no payload", PA + "BaseIndexer.kt:331", "end of an index run with at least one song"))
edge("im-indexed", "im-post", "subscribed", "trigger", flow("Start post-index workers", "ScheduleService subscribes to LibraryIndexed", "an immediate run of three workers", W + "ScheduleService.kt:252", "LibraryIndexed"))

G = "enrich"
node("en-mbworker", "MusicBrainzWorker", G, "Assigns MusicBrainz ids to songs, albums and artists that have none, then merges duplicate albums.", "cron 0 0 * * *, post-index", None, W + "MusicBrainzWorker.kt:12")
node("en-identify", "fetchMusicBrainzId", G, "SongService.fetchMusicBrainzId uses an existing id, else an AcoustID fingerprint lookup, else MusicBrainzService.searchMb, which searches by ISRC and then by title and artist. The lookups by id go through CachedMusicBrainzService, searchMb asks MusicBrainz directly.", "SongService", [["AcoustID rule", "score at least 0.85 within 3 s of duration, negative results rechecked after 30 days"]], S + "services/SongService.kt:756")
node("en-fpcalc", "fpcalc", G, "fpcalc -json <path> returns duration and fingerprint.", "CLI tool", None, S + "services/metadata/AcoustIdFingerprintService.kt:16")
node("en-acoustid", "AcoustID", G, "api.acoustid.org/v2/lookup with recordings and release groups. Results land in song_acoustid.", "external", None, S + "services/metadata/AcoustIdService.kt:75")
node("en-httpqueue", "HTTP queue", G, "HttpClientQueueService: one priority queue per host with fixed spacing, pacing from X-RateLimit headers and up to 3 attempts on 429.", "per-host pacing",
     [["Spacing", "musicbrainz 1 s, googleapis and youtube 1 s, theaudiodb 500 ms, acoustid 340 ms, deezer, spotify and apple 100 ms, linkresolver and listenbrainz 10 ms, others 250 ms"], ["Priorities", "HIGH, NORMAL, LOW"]], S + "core/HttpClient.kt:131")
node("en-mbmirror", "mb_* mirror", G, "CachedMusicBrainzService reads the mb_* tables first and writes through on a miss.", "read-through cache", [["Tables", "mb_artist, mb_recording, mb_release, mb_release_group, credits, ISRCs, mb_relation, mb_relation_provider"]], S + "services/metadata/MusicBrainzService.kt:655")
node("en-mb", "MusicBrainz", G, "musicbrainz.org/ws/2 with User-Agent Synara/<version>, 3 attempts on 503, 429 and 5xx.", "external", None, S + "services/metadata/MusicBrainzService.kt:63")
node("en-mbcache", "MusicBrainzCacheWorker", G, "Refetches mirror rows older than 90 days at LOW priority, caches recordings referenced by listens and fetches release-group covers for listened groups.", "after musicbrainz-worker", None, W + "MusicBrainzCacheWorker.kt:29")
node("en-isrc", "IsrcProviderEnrichment", G, "IsrcProviderEnrichmentWorker asks each provider for the track by ISRC and the album by barcode and stores the provider URLs.", "after musicbrainz-cache", [["Writes", "song_provider, album_provider"]], W + "IsrcProviderEnrichmentWorker.kt:19")
node("en-providers", "Provider APIs", G, "Tidal, Spotify, Deezer and Apple Music. getTrackByIsrc and getAlbumByBarcode on every metadata provider except MusicBrainz that supports the feature.", "Tidal, Spotify, Deezer, Apple", None, W + "IsrcProviderEnrichmentWorker.kt:76")
node("en-genre", "GenreMetadataWorker", G, "MetadataFetchingService.fetchAllGenresWithMbId for entities with a MusicBrainz id.", "after musicbrainz-worker", None, W + "GenreMetadataWorker.kt:7")
node("en-artistimg", "ArtistImageWorker", G, "MetadataFetchingService.fetchAllArtistImages. Sources run one after the other in the order theAudioDB, tidal, deezer, appleMusic, spotify, limited to those that report supported.", "after genre-metadata", None, W + "ArtistImageWorker.kt:7")
node("en-tadb", "MetadataTheAudioDBWorker", G, "MetadataFetchingService.fetchMetadata from TheAudioDB: genres, biographies and images.", "after artist-image", None, W + "MetadataTheAudioDBWorker.kt:8")
node("en-audiodb", "TheAudioDB", G, "theaudiodb.com with the key in the URL path. The default key is 123.", "external", None, S + "services/metadata/TheAudioDBService.kt:95")
node("en-merge", "Duplicate album merge", G, "LibraryMergeService.mergeDuplicateAlbums, at the end of MusicBrainzWorker and also from DuplicateAlbumMergeTrigger on the AlbumsLinkedToMusicBrainz event.", "LibraryMergeService", None, S + "services/DuplicateAlbumMergeTrigger.kt:12")

edge("en-mbworker", "en-identify", "per entity", "step", flow("Identify", "Song, Album and Artist fetchMusicBrainzId at LOW priority", "entities without a MusicBrainz id", W + "MusicBrainzWorker.kt:44", "cron 0 0 * * * and after every index run"))
edge("en-identify", "en-fpcalc", "fingerprint", "external", flow("Fingerprint", "fpcalc -json <path>", "duration and Chromaprint fingerprint", S + "services/metadata/AcoustIdFingerprintService.kt:41", "no id yet"))
edge("en-identify", "en-acoustid", "lookup", "external", flow("Fingerprint lookup", "HTTPS GET /v2/lookup with client, duration, fingerprint", "recording ids with scores", S + "services/metadata/AcoustIdService.kt:191", "after fpcalc", "ACOUSTID_API_KEY"))
edge("en-identify", "en-mbmirror", "by id", "step",
     flow("Recording by id", "CachedMusicBrainzService.getRecording", "the recording of an existing id or of an AcoustID match", S + "services/SongService.kt:757", "an id is known"),
     flow("Store the found recording", "MusicBrainzCacheService.updateRecordingCache", "the recording found for a song that had no MusicBrainz id, written to the mirror", S + "services/SongService.kt:765", "the song had no id and a recording was found"))
edge("en-identify", "en-httpqueue", "text search", "step",
     flow("Search past the mirror", "MusicBrainzService.searchMb, HTTPS GET /recording with a query through HttpClientQueueService.enqueue", "a query by ISRC, then one by title, artists and album. The mb_* tables are not read for it", S + "services/SongService.kt:760", "no id and no AcoustID match"))
edge("en-mbmirror", "en-httpqueue", "on a miss", "step", flow("Queued request", "HttpClientQueueService.enqueue", "the request with its priority", S + "core/HttpClient.kt:131", "mirror miss"))
edge("en-httpqueue", "en-mb", "1 per second", "external", flow("MusicBrainz request", "HTTPS GET /recording, /release, /release-group, /artist, /url", "entities and relations, written back to the mirror", S + "services/metadata/MusicBrainzService.kt:108"))
edge("en-mbworker", "en-merge", "at the end", "step", flow("Merge duplicates", "LibraryMergeService.mergeDuplicateAlbums", "albums that now share an identity", W + "MusicBrainzWorker.kt:122"))
edge("en-mbworker", "en-mbcache", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload. Chains fire only when the task before them succeeds", W + "ScheduleService.kt:286", "musicbrainz-worker finished"))
edge("en-mbcache", "en-mbmirror", "refresh", "step", flow("Refresh stale rows", "refetch at LOW priority", "rows older than 90 days, recordings from listens, release-group covers", W + "MusicBrainzCacheWorker.kt:104"))
edge("en-mbcache", "en-isrc", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload", W + "ScheduleService.kt:286", "musicbrainz-cache-worker finished"))
edge("en-isrc", "en-providers", "by ISRC, UPC", "external", flow("Provider lookups", "HTTPS GET per provider", "provider ids and URLs for songs and albums", W + "IsrcProviderEnrichmentWorker.kt:111", None, "the credential of each provider"))
edge("en-mbworker", "en-genre", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload", W + "ScheduleService.kt:286", "musicbrainz-worker finished"))
edge("en-genre", "en-artistimg", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload", W + "ScheduleService.kt:286", "genre-metadata-worker finished"))
edge("en-artistimg", "en-audiodb", "images, first", "external", flow("Artist images, first source", "HTTPS GET artist-mb.php for an artist with a MusicBrainz id, else search.php by name", "artist pictures. theAudioDB is first in the order theAudioDB, tidal, deezer, appleMusic, spotify", S + "services/MetadataFetchingService.kt:38", "artists without an image, when the source reports supported"))
edge("en-artistimg", "en-providers", "images", "external", flow("Artist images", "HTTPS GET per source", "artist pictures from tidal, deezer, appleMusic and spotify, after theAudioDB", S + "services/MetadataFetchingService.kt:369"))
edge("en-artistimg", "en-tadb", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload", W + "ScheduleService.kt:286", "artist-image-worker finished"))
edge("en-genre", "en-audiodb", "genres", "external", flow("Genres", "HTTPS GET artist-mb.php, album-mb.php, track-mb.php", "genres for entities with a MusicBrainz id", S + "services/metadata/TheAudioDBService.kt:152"))
edge("en-tadb", "en-audiodb", "metadata", "external", flow("Biographies and images", "HTTPS GET search.php and the *-mb.php lookups", "genres, biographies, artist and album images", S + "services/metadata/TheAudioDBService.kt:123"))

G = "audio"
node("au-analysis", "AudioAnalysisWorker", G, "AudioAnalysisService.analyzeSong per song. Features land in song_audio_data, the RMS timeline is computed in-process.", "cron 0 3 * * *", None, W + "AudioAnalysisWorker.kt:10")
node("au-essentia", "essentia extractor", G, "essentia_streaming_extractor_music writes a temporary essentia_*.json. Dockerfile.nobuild copies the binary into the image.", "CLI tool", None, S + "services/AudioAnalysisService.kt:62")
node("au-embedding", "AudioEmbeddingWorker", G, "AudioEmbeddingService sends batches of 16 file paths with a 15 minute timeout and stores the vectors as float32 blobs in song_audio_embedding. A batch answered with another status than 200 stores nothing, and its songs stay unembedded for the next run.", "after audio-analysis", None, W + "AudioEmbeddingWorker.kt:8")
node("au-embed", "audio-embed", G, "FastAPI sidecar on port 8200. Essentia MusiCNN at 16 kHz mono, mean-pooled and L2-normalised, model msd-musicnn_v1. It reads the files by the paths the server sends.", "sidecar", None, "audio-embed/main.py:45")
node("au-timeline", "AudioTimelineBackfill", G, "AudioTimelineBackfillWorker runs analyzeSong for songs without a timeline and refreshEnvelopes for songs with an outdated one, within 6 hours.", "after audio-analysis", None, W + "AudioTimelineBackfillWorker.kt:12")
node("au-flac", "FlacAnalysisWorker", G, "metaflac --list into flac_info, and --add-seekpoint=2s --add-padding=8192 as a fix.", "cron 0 5 * * *", None, W + "FlacAnalysisWorker.kt:9")
node("au-pcm", "PcmAnalysisWorker", G, "ffprobe for the stream layout and ffmpeg -f md5 for the audio hash, into pcm_info.", "cron 20 5 * * *", None, W + "PcmAnalysisWorker.kt:8")
node("au-start", "AudioStartAnalysisWorker", G, "AudioStartAnalysisService.analyze, in-process.", "cron 40 5 * * *, post-index", None, W + "AudioStartAnalysisWorker.kt:9")
node("au-transcode", "AutoTranscodeWorker", G, "Transcoder.transcodeAudio per configured Opus or AAC quality, in-process through JavaCV. Results are tracked by TranscodedSongRepository.", "cron 0 3 * * *", None, W + "AutoTranscodeWorker.kt:20")
node("au-files", "Audio files", G, "The music directories on disk. The analysis workers read them, the transcoder writes its copies under the transcode path.", "music directories", None, S + "services/StorageService.kt:68")
node("au-dirty", "Dirty flag", G, "RecommendationService.markDirty. True at boot, set again by new audio embeddings, by ListenIngested and by PlaylistChanged.", "RecommendationService", None, S + "services/RecommendationService.kt:37")
node("au-train", "RecommendationTrainWorker", G, "trainIfDirty writes the training files, waits up to 5 minutes for pickup and up to 6 hours for the result, then upserts song_embedding in batches of 1000.", "cron 0 7 * * *", [["Files written", "songs.jsonl, sequences.jsonl, meta.json, request.ready"], ["Sequences", "user playlists, playlists, albums and listen sessions split at a 30 minute gap"]], W + "RecommendationTrainWorker.kt:7")
node("au-recsys", "recsys", G, "Python loop without a port. It polls the shared directory every 5 s, trains Word2Vec skip-gram, a Ridge mapping from audio to behaviour for cold songs, and MiniBatchKMeans with the top genre per cluster as mood.", "sidecar, shared volume", [["Files written", "embeddings.jsonl, result.ready or result.failed"]], "recsys/main.py:124")
node("au-songemb", "song_embedding", G, "64-dimensional vector, cluster and mood per song.", "table", None, S + "services/RecommendationService.kt:289")
node("au-serving", "RecommendationServing", G, "RecommendationServingService keeps an in-memory index keyed by the newest updatedAt and serves similarSongs, mix, moodPlaylist and moods.", "in-memory index", None, S + "services/RecommendationServingService.kt:33")
node("au-consumers", "Radio, discovery, covers", G, "RadioService, DiscoveryService and the IRecommendationService RPC use RecommendationServingService. CoverSourceCollector reads the moods from song_embedding directly for generated covers.", "consumers", None, S + "services/RadioService.kt:24")

edge("au-analysis", "au-essentia", "extract", "external", flow("Feature extraction", "essentia_streaming_extractor_music", "audio features as JSON, stored in song_audio_data", S + "services/AudioAnalysisService.kt:105", "cron 0 3 * * *", "binary on PATH"))
edge("au-analysis", "au-embedding", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload", W + "ScheduleService.kt:286", "audio-analysis finished"))
edge("au-analysis", "au-timeline", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload", W + "ScheduleService.kt:286", "audio-analysis finished"))
edge("au-embedding", "au-embed", "POST /embed", "external", flow("Embeddings", "HTTP POST /embed {paths[]}", "16 paths per batch, returns one vector per path", S + "services/AudioEmbeddingService.kt:54", None, "AUDIO_EMBED_URL"))
edge("au-embedding", "au-dirty", "markDirty", "step", flow("New vectors", "RecommendationService.markDirty", "a flag, set when at least one vector was stored", W + "AudioEmbeddingWorker.kt:31"))
edge("au-dirty", "au-train", "only when dirty", "trigger", flow("Nightly training", "trainIfDirty", "nothing when the flag is clear", S + "services/RecommendationService.kt:41", "cron 0 7 * * *"))
edge("au-train", "au-recsys", "files", "external",
     flow("Training request", "files in RECSYS_DATA_DIR: songs.jsonl, sequences.jsonl, meta.json, then request.ready", "song ids with audio vectors and genres, and listening sequences", S + "services/RecommendationService.kt:52", "worker run", "RECSYS_DATA_DIR"),
     flow("Result pickup", "the server polls every 2 s for result.ready or result.failed, then reads embeddings.jsonl", "id, vector, cluster and mood per song", S + "services/RecommendationService.kt:254"))
edge("au-train", "au-songemb", "upsert", "step", flow("Store the model", "upserts in batches of 1000 lines", "vectors, clusters and moods", S + "services/RecommendationService.kt:289"))
edge("au-songemb", "au-serving", "index", "step", flow("Load the index", "RecommendationServingService", "all vectors, reloaded when the newest updatedAt changes", S + "services/RecommendationServingService.kt:33", "first use after a change"))
edge("au-serving", "au-consumers", "serves", "step", flow("Recommendations", "similarSongs, mix, moodPlaylist, moods", "ranked songs and mood names", S + "services/RecommendationServingService.kt:60", "radio, discovery and the recommendation RPC"))

edge("au-flac", "au-files", "metaflac", "external", flow("FLAC stream info", "metaflac --list <file>, and --add-seekpoint=2s --add-padding=8192 <file> as a fix", "stream info into flac_info", S + "services/FlacAnalysisService.kt:86", "cron 0 5 * * *", "metaflac on PATH"))
edge("au-pcm", "au-files", "ffprobe, ffmpeg", "external", flow("PCM info", "ffprobe -show_entries ... and ffmpeg -v error -i f -map 0:a:0 -f md5 -", "stream layout and an audio MD5 into pcm_info", S + "services/PcmAnalysisService.kt:79", "cron 20 5 * * *", "ffprobe and ffmpeg on PATH"))
edge("au-start", "au-files", "reads", "step", flow("Start analysis", "AudioStartAnalysisService.analyze, in-process", "where the audio starts in each file", S + "services/AudioStartAnalysisService.kt:63", "cron 40 5 * * * and after every index run"))
edge("au-transcode", "au-files", "writes", "step", flow("Transcodes", "Transcoder.transcodeAudio, in-process", "Opus or AAC copies under the transcode path", W + "AutoTranscodeWorker.kt:71", "cron 0 3 * * *"))
edge("au-analysis", "au-files", "reads", "step", flow("RMS timeline", "RmsEnvelopeExtractor, in-process", "loudness envelopes per song", S + "services/audio/RmsEnvelopeExtractor.kt:27", "cron 0 3 * * *"))

G = "lyrics"
node("ly-lrcworker", "LrcLibWorker", G, "Fetches lyrics for songs without any whose last attempt is more than 7 days old and prefers synced over plain lyrics. It writes the song table directly and records the change.", "cron 30 4 * * *", None, W + "LrcLibWorker.kt:24")
node("ly-lrclib", "LRCLIB", G, "lrclib.net/api/get by artist, track, album and duration.", "external", None, S + "services/LrcLibService.kt:13")
node("ly-syncworker", "LyricsSyncWorker", G, "Waits up to 10 x 30 s for the transcriber, then processes songs without a synced_lyrics row one at a time, songs with lyrics first. It writes provider not_found when there is nothing to align.", "cron 0 4 * * *", None, W + "LyricsSyncWorker.kt:18", ["disabled-by-default"])
node("ly-transcribe", "transcribeLyrics", G, "LyricsService.transcribeLyrics picks the lyrics text in this order: the request, synced_lyrics.rawLyrics, song.lyrics, then the syncedlyrics tool. It sends the file path to the transcriber with a 15 minute timeout.", "LyricsService", None, S + "services/LyricsService.kt:52")
node("ly-syncedlyrics", "syncedlyrics", G, "python3 -u -m syncedlyrics with a 5 minute cap. Also used by the lyrics search RPC.", "CLI tool", None, S + "services/LyricsSearch.kt:18")
node("ly-transcriber", "transcriber", G, "FastAPI sidecar on port 8000. With lyrics supplied it separates vocals with Demucs, transcribes with faster-whisper medium, maps the result onto the official lines and aligns phonemes with WhisperX, falling back from CUDA to CPU. It reads the audio by the absolute path the server sends.", "sidecar", None, "transcriber/main.py:118")
node("ly-songlyrics", "song.lyrics", G, "The lyrics column of the song table. LrcLibWorker fills it for songs where it is empty. transcribeLyrics reads it as the text to align.", "column", None, W + "LrcLibWorker.kt:73")
node("ly-table", "synced_lyrics", G, "The SyncedLyrics answer of the transcriber as CBOR in content, with the raw lyrics text and provider whisperx_v1.", "table", None, S + "services/LyricsService.kt:116")

edge("ly-lrcworker", "ly-lrclib", "GET /api/get", "external", flow("Lyrics lookup", "HTTPS GET /api/get", "synced or plain lyrics", S + "services/LrcLibService.kt:13", "cron 30 4 * * *"))
edge("ly-syncworker", "ly-transcribe", "per song", "step", flow("Align one song", "LyricsService.transcribeLyrics", "a song id", W + "LyricsSyncWorker.kt:74", "cron 0 4 * * *, disabled by default"))
edge("ly-transcribe", "ly-syncedlyrics", "fallback", "external", flow("Find lyrics text", "LyricsSearch.searchLyrics runs python3 -u -m syncedlyrics -v -o <tmp.lrc>", "lyrics text when the database has none", S + "services/LyricsService.kt:77", "no lyrics stored"))
edge("ly-transcribe", "ly-transcriber", "POST /transcribe", "external",
     flow("Transcription", "HTTP POST /transcribe {path, artist, title, lyrics?}", "the file path and lyrics, returns SyncedLyrics", S + "services/LyricsService.kt:86", "worker or the transcribeLyrics RPC", "TRANSCRIBER_URL, default http://localhost:8000"),
     flow("Health check", "HTTP GET /health", "readiness", S + "services/LyricsService.kt:140", "before a worker run"))
edge("ly-transcribe", "ly-table", "store", "step", flow("Store timings", "upsert plus a SONG updated event", "CBOR timings with provider whisperx_v1", S + "services/LyricsService.kt:116"))
edge("ly-syncworker", "ly-table", "not_found", "step", flow("Mark a song without lyrics", "SyncedLyricsTable.upsert", "a row with no content and provider not_found", W + "LyricsSyncWorker.kt:79", "transcribeLyrics returned nothing and the song has no lyrics"))
edge("ly-lrcworker", "ly-songlyrics", "update", "step", flow("Store lyrics", "SongTable.update in dbQuery, then entityEvents.recordChanges", "synced lyrics when LRCLIB has them, else plain lyrics, with lastLyricsFetchAttempt", W + "LrcLibWorker.kt:73", "LRCLIB returned lyrics"))
edge("ly-transcribe", "ly-songlyrics", "reads", "step", flow("Text for alignment", "song.lyrics from songService.byId", "the stored lyrics, used when the request and synced_lyrics.rawLyrics have none", S + "services/LyricsService.kt:70"))

G = "releases"
node("re-follow", "followArtist", G, "ReleaseService.followArtist, called by the followArtist RPC at HIGH priority, fetches the MusicBrainz artist, upserts followed_artist, backfills release images and starts an Apple fetch.", "RPC", None, S + "services/ReleaseService.kt:62")
node("re-worker", "RecentReleaseWorker", G, "ReleaseService.fetchNewReleases for followed artists first, then for library artists that are not followed.", "cron 0 1 * * *", None, W + "RecentReleaseWorker.kt:7")
node("re-artist", "Per artist", G, "MusicBrainz releases by artist and their release groups.", "ReleaseService", None, S + "services/ReleaseService.kt:1115")
node("re-group", "Per release group", G, "processReleaseGroup loads releases and recordings of the group, resolves the URL relations through LinkResolver, and searches Apple when no Apple link exists and Tidal when no Tidal link exists.", "processReleaseGroup", None, S + "services/ReleaseService.kt:1232")
node("re-mb", "MusicBrainz", G, "Releases, release groups, recordings and URL relations, through the mirror at LOW priority. A manual refresh uses HIGH.", "external", None, S + "services/ReleaseService.kt:1151")
node("re-linkresolver", "LinkResolver", G, "Platform links for the URL relations of a release.", "external", None, S + "services/ReleaseService.kt:1297")
node("re-search", "Apple and Tidal search", G, "AppleMusicService.searchAlbums and TidalService.searchAlbums, each for releases without a link on that platform.", "external", None, S + "services/ReleaseService.kt:1371")
node("re-caa", "Cover Art Archive", G, "coverartarchive.org release-group fronts, fetched in processReleaseGroup when the release has followers.", "external", None, S + "services/ReleaseService.kt:1536")
node("re-links", "ProviderLinkService", G, "Persists links into provider_link, recent_release_link and provider_release_link.", "link tables", None, S + "services/release/ProviderLinkService.kt:26")
node("re-enrich", "ProviderEnrichmentWorker", G, "AlbumService and SongService.enrichProviders for albums, songs and singles, using the MusicBrainz mirror and LinkResolver.", "after recent-release", [["Writes", "song_provider, album_provider"]], W + "ProviderEnrichmentWorker.kt:11")
node("re-appleworker", "AppleMusicReleaseWorker", G, "AppleMusicReleaseService.fetchFollowedArtistReleases. Window of 180 days back and 365 days ahead, links are resolved again after 7 days.", "cron 0 6 * * *", None, W + "AppleMusicReleaseWorker.kt:7")
node("re-resolver", "AppleMusicArtistResolver", G, "Finds the Apple artist id in this order: a stored id, a MusicBrainz relation, then evidence from ISRC, barcode and gamdl album lookups. The result is kept in artist_provider.", "identity by evidence", None, S + "services/metadata/AppleMusicArtistResolver.kt:37")
node("re-apple", "Apple Music catalog", G, "api.music.apple.com with a developer token. AppleMusicReleaseService downloads release artwork through ApiClient.instance, outside the per-host queue.", "external", None, S + "services/metadata/AppleMusicService.kt:333")
node("re-provrelease", "provider_release", G, "Apple releases matched to MusicBrainz by barcode and URL.", "table", None, S + "services/release/AppleMusicReleaseService.kt:579")

edge("re-follow", "re-mb", "artist", "external", flow("Artist lookup", "MusicBrainzService.fetchArtistById", "the MusicBrainz artist", S + "services/ReleaseService.kt:100", "user follows an artist"))
edge("re-follow", "re-resolver", "kick off", "step", flow("Apple fetch on follow", "AppleMusicReleaseService.fetchArtistReleasesAsync", "the artist", S + "services/ReleaseService.kt:85", "user follows an artist"))
edge("re-worker", "re-artist", "per artist", "step", flow("Fetch new releases", "ReleaseService.fetchNewReleases", "followed artists, then unfollowed library artists", S + "services/ReleaseService.kt:930", "cron 0 1 * * *"))
edge("re-artist", "re-mb", "releases", "external", flow("Releases by artist", "HTTPS GET /release and /release-group by artist", "releases and release groups", S + "services/ReleaseService.kt:1115"))
edge("re-artist", "re-group", "per group", "step", flow("Process a group", "processReleaseGroup", "a release group", S + "services/ReleaseService.kt:1232"))
edge("re-group", "re-mb", "group detail", "external", flow("Group detail", "HTTPS GET releases and recordings by release-group", "editions and tracks", S + "services/ReleaseService.kt:1292"))
edge("re-group", "re-linkresolver", "URL relations", "external", flow("Resolve relations", "HTTPS GET /resolve", "platform links", S + "services/ReleaseService.kt:1297", None, "LINKRESOLVER_API_KEY"))
edge("re-group", "re-search", "no link yet", "external", flow("Album search", "Apple searchAlbums, then Tidal searchAlbums", "candidate albums", S + "services/ReleaseService.kt:1371", "no link for that platform yet"))
edge("re-group", "re-caa", "cover", "external", flow("Release cover", "HTTPS GET release-group front", "the cover", S + "services/ReleaseService.kt:1536", "the release has followers"))
edge("re-group", "re-links", "persist", "step", flow("Store links", "ProviderLinkService.attachRecentReleaseTx", "provider links per release", S + "services/ReleaseService.kt:1491"))
edge("re-worker", "re-enrich", "after success", "trigger", flow("Task chain", "TaskCompletionTrigger", "no payload", W + "ScheduleService.kt:286", "recent-release-worker finished"))
edge("re-enrich", "re-linkresolver", "enrichProviders", "external", flow("Cross-platform links", "HTTPS GET /resolve", "links for library songs and albums", S + "services/SongService.kt:1283", None, "LINKRESOLVER_API_KEY"))
edge("re-appleworker", "re-resolver", "per artist", "step", flow("Resolve the Apple artist", "AppleMusicArtistResolver.resolve", "a followed artist", S + "services/release/AppleMusicReleaseService.kt:314", "cron 0 6 * * *", "applemusic.developer credential"))
edge("re-resolver", "re-apple", "lookups", "external",
     flow("Evidence lookups", "HTTPS GET songs?filter[isrc], albums?filter[upc]", "catalog entries that prove the artist id", S + "services/metadata/AppleMusicArtistResolver.kt:37"),
     flow("Catalog albums", "HTTPS GET artists/{id}/albums", "the artist's catalog albums, filtered to the window afterwards", S + "services/release/AppleMusicReleaseService.kt:315"))
edge("re-resolver", "re-provrelease", "upsert", "step", flow("Store releases", "match by barcode and URL, then upsert", "Apple releases with artwork", S + "services/release/AppleMusicReleaseService.kt:579"))
edge("re-resolver", "re-linkresolver", "links", "external", flow("Links for Apple releases", "HTTPS GET /resolve", "links on other platforms", S + "services/release/AppleMusicReleaseService.kt:524"))

G = "podcasts"
node("po-subscribe", "subscribe", G, "RpcPodcastService.subscribe(feedUrl) calls PodcastFeedService.subscribe, which fetches the feed through fetchFeed.", "RPC", None, S + "services/podcast/RpcPodcastService.kt:41")
node("po-refresh", "PodcastRefreshWorker", G, "PodcastFeedService.refreshShow per show, then a scan of the local podcast library, then maintenance: queue imports, apply retention, purge. It triggers the import worker when episodes were queued.", "cron 15 * * * *", None, W + "PodcastRefreshWorker.kt:11")
node("po-guard", "requirePublicHttpUrl", G, "Feed, artwork, enclosure and stream URLs must be http or https and resolve to a public host, as a guard against requests into the local network.", "SSRF guard", None, S + "services/podcast/PodcastHttp.kt:46")
node("po-feed", "Feed hosts", G, "Conditional GET with ETag and Last-Modified on the podcast-feed client, 60 s timeout. Artwork uses the same client.", "external", None, S + "services/podcast/PodcastFeedService.kt:50")
node("po-import", "PodcastImportWorker", G, "PodcastImportService.processQueue with 2 downloads at a time. Also reachable through the importEpisode RPC.", "cron */15 * * * *", None, W + "PodcastImportWorker.kt:7")
node("po-media", "Enclosure hosts", G, "Episode audio from the publisher, on the podcast-media client without a request timeout.", "external", None, S + "services/podcast/PodcastImportService.kt:83")
node("po-probe", "PodcastMediaProbe", G, "In-process probe of the downloaded file. Embedded lyrics become a transcript.", "JavaCV", None, S + "services/podcast/PodcastMediaProbe.kt:57")
node("po-stream", "PodcastStreamService", G, "Episodes that are not imported are stream-proxied with Range requests to the publisher host.", "streamEpisode", None, S + "services/podcast/PodcastStreamService.kt:65")
node("po-transcript", "Transcripts", G, "getTranscript returns stored content or a sidecar file, else downloads the transcript URL on demand with a size cap and stores it. No file under services/podcast refers to the transcriber.", "getTranscript", None, S + "services/podcast/PodcastService.kt:462")
node("po-index", "Podcast Index", G, "api.podcastindex.org search by term, signed with sha1 of key, secret and date.", "external", None, S + "services/podcast/index/PodcastIndexOrgIndex.kt:114")
node("po-tables", "podcast tables", G, "podcastShow, podcastEpisode, podcastSubscription, podcastEpisodeProgress, podcastTranscript.", "tables", None, S + "services/podcast/PodcastService.kt:462")

edge("po-subscribe", "po-guard", "feed URL", "step", flow("Check the URL", "requirePublicHttpUrl", "the feed URL", S + "services/podcast/PodcastHttp.kt:46", "user subscribes"))
edge("po-refresh", "po-guard", "per show", "step", flow("Refresh a show", "PodcastFeedService.refreshShow", "the stored feed URL with ETag and Last-Modified", S + "services/podcast/PodcastFeedService.kt:103", "cron 15 * * * *"))
edge("po-guard", "po-feed", "conditional GET", "external", flow("Fetch the feed", "HTTPS GET with If-None-Match and If-Modified-Since", "RSS and artwork", S + "services/podcast/PodcastFeedService.kt:53"))
edge("po-guard", "po-tables", "upsert", "step", flow("Store shows and episodes", "PodcastService", "show and episode rows", S + "services/podcast/PodcastFeedService.kt:103"))
edge("po-refresh", "po-import", "triggers", "trigger", flow("Queue imports", "maintenance queues episodes and triggers podcast-import", "episodes to import", W + "PodcastRefreshWorker.kt:59", "after a refresh, when episodes were queued"))
edge("po-import", "po-media", "download", "external", flow("Download the enclosure", "HTTPS GET, 2 in parallel", "episode audio", S + "services/podcast/PodcastImportService.kt:83", "cron */15 * * * * or the importEpisode RPC"))
edge("po-import", "po-probe", "probe", "step", flow("Probe the file", "PodcastMediaProbe", "duration and embedded lyrics", S + "services/podcast/PodcastImportService.kt:126"))
edge("po-probe", "po-tables", "store", "step", flow("Mark imported", "PodcastImportService", "the local file and its transcript", S + "services/podcast/PodcastImportService.kt:129"))
edge("po-stream", "po-media", "Range", "external", flow("Stream proxy", "HTTPS GET with Range", "audio for episodes that are not imported", S + "services/podcast/PodcastStreamService.kt:66", "playback"))
edge("po-transcript", "po-feed", "fetch", "external", flow("Transcript fetch", "HTTPS GET, size-capped", "the transcript file named in the feed", S + "services/podcast/PodcastService.kt:1139", "getTranscript, content not stored yet"))
edge("po-subscribe", "po-index", "searchIndex", "external", flow("Directory search", "HTTPS GET /api/1.0/search/byterm", "shows matching a term", S + "services/podcast/RpcPodcastService.kt:52", "searchIndex RPC", "podcastindex.api credential"))

G = "hue"
node("hu-discover", "Discovery", G, "HueDiscoveryService.discover looks for _hue._tcp.local. over mDNS with JmDNS, adds the bridges from the cloud lookup, and keeps the candidates that answer a config probe.", "discover RPC", None, S + "services/hue/HueDiscoveryService.kt:38")
node("hu-cloud", "discovery.meethue.com", G, "Cloud lookup that lists bridges.", "external", None, S + "services/hue/HueDiscoveryService.kt:114")
node("hu-pair", "Pairing", G, "startPairing asks the bridge for an application key while the link button is pressed. Username and clientkey go into hue_bridge. The bridge certificate is pinned on first use.", "startPairing", None, S + "services/hue/HueService.kt:210")
node("hu-event", "NowPlayingChanged", G, "Emitted by ScrobbleService on nowPlaying, reportPlayback and clearNowPlaying, and when the playback lease expires.", "event", None, S + "services/ScrobbleService.kt:71")
node("hu-handle", "handleNowPlaying", G, "HueService combines the cover palette from ImageService, the Essentia data from AudioAnalysisService and the timeline scores, maps them with HuePaletteMapper and schedules the motion.", "HueService", None, S + "services/hue/HueService.kt:523")
node("hu-clip", "Bridge CLIP v2", G, "https://{ip}/clip/v2/resource/... with the header hue-application-key. HueService thins beat-driven light commands against MAX_LIGHT_COMMANDS_PER_SECOND, which is 8.", "local network", None, S + "services/hue/HueBridgeClient.kt:119")
node("hu-stream", "Entertainment stream", G, "A PUT on entertainment_configuration starts streaming, then DTLS 1.2 with PSK over UDP port 2100. HueStream frames go out every 40 ms.", "DTLS, 25 fps", None, S + "services/hue/HueEntertainmentStream.kt:135")

edge("hu-discover", "hu-cloud", "cloud lookup", "external", flow("Cloud lookup", "HTTPS GET https://discovery.meethue.com/", "bridge addresses, merged with the mDNS results", S + "services/hue/HueDiscoveryService.kt:42", "discover RPC"))
edge("hu-discover", "hu-pair", "bridge chosen", "step", flow("Pair", "IHueService.startPairing as a flow of HuePairingStatus", "the bridge address", S + "services/hue/HueService.kt:210", "user action"))
edge("hu-pair", "hu-clip", "POST /api", "external", flow("Link-button pairing", "HTTPS GET /api/0/config and POST /api", "username and clientkey", S + "services/hue/HueBridgeClient.kt:72"))
edge("hu-event", "hu-handle", "subscribed", "trigger", flow("Now playing", "hooks.on<NowPlayingChanged> in startService", "user, song, position and playing state", S + "services/hue/HueService.kt:143", "playback starts, moves or stops"))
edge("hu-handle", "hu-clip", "light commands", "external", flow("Scene and light state", "HTTPS PUT /clip/v2/resource/{light|grouped_light|scene}", "colours and brightness", S + "services/hue/HueBridgeClient.kt:125", None, "paired application key"))
edge("hu-handle", "hu-stream", "frames", "external", flow("Light stream", "DTLS-PSK over UDP 2100, identity is the application key", "HueStream frames at 25 fps", S + "services/hue/HueEntertainmentSession.kt:38", None, "an entertainment configuration on the bridge"))

G = "listening"
node("li-scrobble", "Scrobble", G, "IScrobbleService.listened from a client.", "RPC", None, S + "services/ScrobbleService.kt:117")
node("li-local", "ingestLocal", G, "ListenService writes a listen row with source LOCAL and notifies the LISTENS topic.", "ListenService", None, S + "services/ListenService.kt:134")
node("li-table", "listen", G, "All listens, local and imported, with updatedAt as the cursor column for the backup.", "table", None, S + "services/ListenService.kt:134")
node("li-lbworker", "ListenBrainzSyncWorker", G, "syncAllAccounts pulls every linked account. The watermark only advances when a run completes.", "cron 0 * * * *", None, W + "ListenBrainzSyncWorker.kt:7")
node("li-lb", "ListenBrainz", G, "api.listenbrainz.org. Listens are only pulled. The one write is a manual mapping submission with the user's token.", "external", None, S + "services/sync/ListenBrainzService.kt:474")
node("li-ingestlb", "ingestListenBrainz", G, "ListenService stores the pulled listens with source LISTENBRAINZ and emits ListenIngested.", "ListenService", None, S + "services/ListenService.kt:62")
node("li-match", "Matching", G, "ListenBrainzService.toIncomingListens matches a listen to a song before it is stored: recording id to ISRC through mb_recording_isrc, then song.isrc and song_musicbrainz, with manual listen_link overrides. rematchUnmatched repeats it for stored listens.", "ListenBrainzService", None, S + "services/sync/ListenBrainzService.kt:433")
node("li-event", "ListenIngested", G, "Emitted by ingestListenBrainz. RecommendationPlugin reacts with RecommendationService.markDirty.", "event", None, S + "services/ListenService.kt:90")
node("li-backupworker", "ListenBackupWorker", G, "ListenBackupService.sync sends LOCAL listens newer than the cursor in batches of the configured size, 1000 by default, and stores the cursor after every acknowledged batch. It does nothing when the backup is disabled or has no URL.", "cron 30 * * * *", None, W + "ListenBackupWorker.kt:7")
node("li-backup", "listen-backup", G, "Receiver on port 8082 by default that stores the listens in its own database. Its routes are GET health, GET status and POST listens.", "satellite", None, "listen-backup/src/main/kotlin/dev/dertyp/listenbackup/Application.kt:84")

edge("li-scrobble", "li-local", "listened", "step", flow("Record a listen", "ScrobbleService.listened to ListenService.ingestLocal", "song, time and milliseconds played", S + "services/ScrobbleService.kt:117", "playback"))
edge("li-local", "li-table", "insert", "step", flow("Store", "insert with listenSource LOCAL", "the listen with names and MusicBrainz ids copied from the song", S + "services/ListenService.kt:134"))
edge("li-lbworker", "li-lb", "pulls", "external",
     flow("Listen history", "HTTPS GET /1/user/{name}/listens?count=1000, optional Authorization: Token", "listens older than max_ts, 4 attempts with linear backoff", S + "services/sync/ListenBrainzService.kt:243", "cron 0 * * * *, link and syncNow", "per-user link rows"),
     flow("Manual mapping", "HTTPS POST /1/metadata/submit_manual_mapping", "a recording id for a listen without one", S + "services/sync/ListenBrainzService.kt:296", "user action", "the user's ListenBrainz token"))
edge("li-lbworker", "li-match", "per page", "step", flow("Match pulled listens", "ListenBrainzService.toIncomingListens", "the listens of a page that are newer than the watermark, each with a song id when one is found", S + "services/sync/ListenBrainzService.kt:215", "each fetched page, before anything is stored"))
edge("li-match", "li-ingestlb", "matched listens", "step", flow("Store pulled listens", "ListenService.ingestListenBrainz", "IncomingListen entries with source LISTENBRAINZ, songId already set where a match was found", S + "services/sync/ListenBrainzService.kt:217", "after the page was matched"))
edge("li-match", "li-table", "rematch", "step", flow("Rematch stored listens", "ListenBrainzService.rematchUnmatched updates ListenTable", "a songId for stored listens without one, from listen_link overrides and recording ids", S + "services/sync/ListenBrainzService.kt:418", "start of an account sync, before the pull"))
edge("li-ingestlb", "li-table", "insert", "step", flow("Store", "insert with listenSource LISTENBRAINZ", "matched and unmatched listens", S + "services/ListenService.kt:62"))
edge("li-ingestlb", "li-event", "emits", "trigger", flow("Announce", "hooks.emit(ListenIngested)", "the account and the number of listens", S + "services/ListenService.kt:90"))
edge("li-backupworker", "li-table", "reads", "step", flow("Select the batch", "LOCAL rows with updatedAt above the cursor, ordered by updatedAt and id", "up to 1000 listens", S + "services/sync/ListenBackupService.kt:288", "cron 30 * * * *"))
edge("li-backupworker", "li-backup", "POST /listens", "external", flow("Send the batch", "HTTP POST /listens with X-Backup-Key", "ListenBackupBatch with the server id. The reply must acknowledge the full batch", S + "services/sync/ListenBackupService.kt:319", None, "the listen_backup_config row, enabled with a URL"))

G = "search"
node("se-write", "Library write", G, "Any insert, update or delete on song, album, artist, their artist links, their MusicBrainz links and artist aliases.", "PostgreSQL")
node("se-trigger", "Indexing triggers", G, "Nine *_change_indexing_trigger row triggers call trigger functions that call queue_for_search_indexing.", "PostgreSQL only", None, "server/src/main/resources/db/migrations/postgres/B1_109__Base.sql:1926")
node("se-queue", "search_index_queue", G, "Entities waiting for their search text to be rebuilt.", "table", None, S + "services/SearchIndexWorker.kt:65")
node("se-worker", "SearchIndexWorker", G, "A continuous loop, not a scheduled task, started only on PostgreSQL. It waits 2 s when the queue is empty, takes batches of 100, rebuilds the weighted tsvector and deletes the queue rows.", "loop, 2 s poll", None, S + "services/SearchIndexWorker.kt:21")
node("se-vector", "search_vector", G, "tsvector columns on song, album and artist with a GIN index.", "columns", None, "server/src/main/resources/db/migrations/postgres/B1_109__Base.sql:1768")
node("se-redis", "RediSearch", G, "Indices {prefix}:song-index, artist-index and album-index over hashes, created by initIndex at startup. Used only when REDIS_USE_SEARCH is true and a Redis host is set.", "optional", None, S + "services/RedisSearchService.kt:19", ["disabled-by-default"])
node("se-remover", "SearchIndexRemover", G, "Removes documents from RediSearch when entities are deleted or merged.", "after commit", None, S + "services/SearchIndexRemover.kt:14")
node("se-rebuild", "SearchIndexRebuildWorker", G, "Queues every song, album and artist. Manual only, and it does nothing on SQLite.", "manual", None, W + "SearchIndexRebuildWorker.kt:14")
node("se-query", "rankedSearchQuery", G, "With RediSearch it runs FT.SEARCH with (tok* | %tok%) and orders the SQL query by the returned ids. Empty results or errors fall back to the database search.", "search request", None, S + "core/Query.kt:101")
node("se-sqlite", "SQLite path", G, "SearchIndexWorker is not started and the rebuild task does nothing, so no search vectors and no Redis documents are written. databaseRankedSearch scores ILIKE '%token%' matches.", "ILIKE", None, S + "core/Query.kt:301")

edge("se-write", "se-trigger", "fires", "trigger", flow("Row change", "row trigger", "the changed row", "server/src/main/resources/db/migrations/postgres/B1_109__Base.sql:1926", "insert, update or delete"))
edge("se-trigger", "se-queue", "insert", "step", flow("Queue the entity", "queue_for_search_indexing", "entity type and id", "server/src/main/resources/db/migrations/postgres/B1_109__Base.sql:1"))
edge("se-rebuild", "se-queue", "queue all", "step", flow("Full rebuild", "insertIgnore of every song, album and artist", "all ids", W + "SearchIndexRebuildWorker.kt:42", "manual run"))
edge("se-worker", "se-queue", "polls", "step", flow("Take a batch", "select, then delete after processing", "up to 100 queued entities", S + "services/SearchIndexWorker.kt:65", "every 2 s when idle"))
edge("se-worker", "se-vector", "rebuild", "step", flow("Rebuild the search text", "update with weighted tsvector", "names, artists, albums and metadata", S + "services/SearchIndexWorker.kt:139"))
edge("se-worker", "se-redis", "HSET", "external", flow("Index document", "Jedis HSET into {prefix}:song:, :artist:, :album:", "the same text as weighted fields", S + "services/SearchIndexWorker.kt:109", None, "REDIS_HOST and REDIS_USE_SEARCH=true"))
edge("se-remover", "se-redis", "DEL", "external", flow("Remove documents", "RedisSearchService.remove", "ids of deleted or merged songs, albums and artists", S + "services/SearchIndexRemover.kt:36", "EntitiesDeleted and EntitiesMerged"))
edge("se-query", "se-redis", "FT.SEARCH", "external", flow("Ranked ids", "FT.SEARCH", "ids in rank order", S + "services/RedisSearchService.kt:124", "a ranked search on the song, artist or album table", "REDIS_USE_SEARCH=true"))
edge("se-query", "se-vector", "fallback", "step", flow("Database search", "tsvector match on PostgreSQL", "ranked rows", S + "core/Query.kt:204", "RediSearch off, empty or failing"))
edge("se-query", "se-sqlite", "on SQLite", "step", flow("Pattern search", "ILIKE '%token%' scoring", "ranked rows", S + "core/Query.kt:301", "the database is SQLite"))

G = "backup"
node("ba-worker", "DatabaseBackupWorker", G, "BackupService.createBackup. Also available as an admin RPC.", "cron 0 2 * * *", None, W + "DatabaseBackupWorker.kt:7")
node("ba-dump", "Database dump", G, "DbManagementService writes a zstd stream with a schema-version section and CBOR rows per table. search_index_queue and the tsvector columns are left out.", "database.cbor.zst", None, S + "services/DbManagementService.kt:111")
node("ba-zip", "Backup zip", G, "backup-{timestamp}.zip in BACKUP_DIR with schema.version, database.cbor.zst, files.tree.cbor.zst and images.index.cbor.zst. The file tree is an inventory of the audio directories.", "BACKUP_DIR", [["Default", "~/.config/backups"], ["Rotation", "keeps 10 zips"]], S + "services/BackupService.kt:156")
node("ba-blobs", "Image blob store", G, "Images are deduplicated into {BACKUP_DIR}/blobs by hash. Rotation removes blobs that no zip references.", "blobs/", None, S + "services/BackupService.kt:273")
node("ba-playlists", "UserPlaylistBackupWorker", G, "Writes playlists-{userId}-{timestamp}.json into {BACKUP_DIR}/user-playlists.", "cron 0 2 * * *", None, S + "services/UserPlaylistBackupService.kt:79")
node("ba-check", "BackupSchemaCheck", G, "Refuses a dump that is below the schema base, newer than the server, or unversioned with unfinished custom migrations, before anything is touched.", "restore gate", None, S + "db/BackupSchemaCheck.kt:8")
node("ba-import", "importData", G, "Spools the dump to a temp file, clears and refills all tables in one transaction, then restarts the change tracking. Images missing on disk are copied back from the blob store.", "one transaction", None, S + "services/DbManagementService.kt:145")
node("ba-load", "loadBackup", G, "Admin RPC to restore a zip from BACKUP_DIR.", "admin RPC", None, S + "services/BackupService.kt:334")
node("ba-setup-backup", "SETUP_FROM_BACKUP", G, "At startup, with 0 songs and at most 1 user, the server loads the given backup and halts with exit code 0. A backup refused by BackupSchemaCheck halts it with exit code 1.", "startup", None, S + "Application.kt:129")
node("ba-setup-mirror", "SETUP_FROM_MIRROR_*", G, "At startup, under the same condition, the server mirrors another Synara server over kRPC with isImport and importUsers, then halts with exit code 0.", "startup", None, S + "Application.kt:151")
node("ba-remote", "Remote Synara server", G, "The source of a setup mirror, reached over kRPC at the host and port of SETUP_FROM_MIRROR_URL.", "external", None, S + "services/RemoteMirrorService.kt:222")
node("ba-db", "Database", G, "The restored or mirrored state.", "PostgreSQL / SQLite")

edge("ba-worker", "ba-dump", "createBackup", "step", flow("Dump the database", "DbManagementService export", "all tables except search_index_queue as CBOR rows", S + "services/BackupService.kt:166", "cron 0 2 * * * or the createBackup RPC"))
edge("ba-dump", "ba-zip", "zip entry", "step", flow("Assemble the zip", "BackupService", "the dump, the schema version, the file inventory and the image index", S + "services/BackupService.kt:156"))
edge("ba-worker", "ba-blobs", "copy images", "step", flow("Store image bytes", "content-addressed copy", "images that are not in the blob store yet", S + "services/BackupService.kt:282"))
edge("ba-zip", "ba-blobs", "rotation", "step", flow("Rotate", "rotateBackups", "the oldest zips beyond 10 and unreferenced blobs are deleted", S + "services/BackupService.kt:407", "after a backup"))
edge("ba-load", "ba-check", "version", "step", flow("Check the schema version", "BackupSchemaCheck", "schema.version from the zip", S + "services/BackupService.kt:353", "admin restores a backup"))
edge("ba-setup-backup", "ba-check", "version", "step", flow("Setup from a backup", "backupService.loadBackup, then Runtime.halt(0)", "the zip named by the variable", S + "Application.kt:129", "startup with an empty database", "SETUP_FROM_BACKUP"))
edge("ba-check", "ba-import", "accepted", "step", flow("Import", "DbManagementService.importData, which checks the schema info of the dump again", "the dump", S + "services/BackupService.kt:359"))
edge("ba-import", "ba-db", "refill", "step", flow("Replace the data", "clear and refill in one transaction, single attempt", "every exported table, inside one dbQuery transaction", S + "services/DbManagementService.kt:151"))
edge("ba-import", "ba-blobs", "restore images", "step", flow("Copy images back", "from the blob store", "image files missing on disk", S + "services/BackupService.kt:390"))
edge("ba-setup-mirror", "ba-remote", "kRPC pull", "external", flow("Setup from a mirror", "RemoteMirrorService.startMirror(isImport, importUsers) over kRPC", "users, images, artists, albums, songs with audio, playlists and likes", S + "Application.kt:151", "startup with an empty database", "SETUP_FROM_MIRROR_URL, SETUP_FROM_MIRROR_USERNAME, SETUP_FROM_MIRROR_PASSWORD"))
edge("ba-setup-mirror", "ba-db", "writes", "step", flow("Fill the library", "RemoteMirrorService inserts", "the mirrored rows, with new ids in import mode", S + "services/RemoteMirrorService.kt:355"))

edge("ba-playlists", "ba-db", "reads", "step", flow("Playlist export", "UserPlaylistBackupService.backupAllUsers", "every user's playlists as playlists-{userId}-{timestamp}.json in {BACKUP_DIR}/user-playlists", S + "services/UserPlaylistBackupService.kt:102", "cron 0 2 * * *"))

note("ListenIngested comes from ListenBrainz ingests", "ListenService.ingestListenBrainz emits ListenIngested. ingestLocal writes the row and notifies the LISTENS topic without emitting it. The recommendation dirty flag is therefore set by ListenBrainz ingests, playlist changes and new audio embeddings, and is true at boot.", S + "services/ListenService.kt:90")
note("Task chains continue on success", "runAndNotify runs the task and then notifies the tasks that wait for it. logTask rethrows a failure and runExclusive throws WorkerAlreadyRunningException, so a worker declared with afterTask does not start when the task before it fails or is skipped as already running.", W + "ScheduleService.kt:286")
note("Tools per server image", "Dockerfile installs tidal-dl-ng, syncedlyrics and the patched tiddl. Dockerfile.nobuild installs syncedlyrics, yt-dlp and the patched tiddl, and adds deno, flac and the Essentia extractor. No gamdl install appears in either file.", "Dockerfile.nobuild:79")
note("Apple artwork: one path uses the unqueued client", "AppleMusicReleaseService.fetchArtworkBytes fetches through ApiClient.instance, the shared client without the per-host queue. ReleaseService.fetchProviderArtworkBytes fetches provider artwork through the queue at HIGH priority.", S + "services/release/AppleMusicReleaseService.kt:869")
note("Routes of the listen backup receiver", "The receiver defines GET health, GET status and POST listens. The server selects rows with listenSource LOCAL for it.", "listen-backup/src/main/kotlin/dev/dertyp/listenbackup/Application.kt:84")
note("LyricsSyncWorker is declared with enabled = false", "The worker runs only after it is enabled in the task configuration. TRANSCRIBER_URL defaults to http://localhost:8000.", W + "LyricsSyncWorker.kt:18")
note("REDIS_USE_SEARCH defaults to false", "redis.useSearch reads REDIS_USE_SEARCH with the default false. example.env lists it as false, and no compose file or Dockerfile in the repository sets it.", "server/src/main/resources/application.yaml:23")
note("Contents of a backup zip", "createBackup writes four entries: the schema version, database.cbor.zst, files.tree.cbor.zst and images.index.cbor.zst. The file tree covers the tracks, albums, playlists, custom, transcode and secondary track directories and the directories of each importer.", S + "services/BackupService.kt:161")
note("OdesliService has no callers", "No file under server/src/main references OdesliService apart from its own. It is left out of the pipelines.", S + "services/metadata/OdesliService.kt:68")

def build(root):
    return {
        "id": "pipelines",
        "tab": "Pipelines",
        "noun": "step",
        "blurb": "The processing chains of the server, one lane per pipeline. Solid arrows lead to the next step, dashed arrows are calls to something outside the server, dotted arrows start a step through a schedule, an event or a finished task.",
        "out": "Leads to",
        "inn": "Comes from",
        "layout": {"kind": "lanes", "direction": "LR"},
        "groups": GROUPS,
        "styles": STYLES,
        "nodes": NODES,
        "edges": EDGES,
        "notes": NOTES,
    }
