S = "server/src/main/kotlin/dev/dertyp/"
C = "common-rpc/src/commonMain/kotlin/dev/dertyp/"
P = "proxy/src/main/kotlin/dev/dertyp/proxy/"
CP = "common-proxy/src/main/kotlin/dev/dertyp/proxy/"
M = "mock-server/src/main/kotlin/dev/dertyp/mock/"

GROUPS = [
    {"id": "clients", "label": "Clients", "column": 0, "desc": "Everything that opens a connection to the server."},
    {"id": "proxy", "label": "Proxy path", "column": 1, "desc": "The relay for servers that cannot be reached directly. It carries kRPC WebSockets only."},
    {"id": "mock", "label": "Mock server", "column": 1, "desc": "Stand-in for client development."},
    {"id": "entry", "label": "Entry points", "column": 2, "desc": "Every HTTP and WebSocket route family of the server."},
    {"id": "auth", "label": "Authentication", "column": 3, "desc": "The mechanisms that turn a request into a user."},
    {"id": "chain", "label": "Wrapper chain", "column": 3, "desc": "Dynamic proxies around every service instance, outermost first."},
    {"id": "domains", "label": "RPC service domains", "column": 4, "desc": "The 53 registered kRPC interfaces, grouped by domain."},
    {"id": "push", "label": "Push to clients", "column": 4, "desc": "Server to client updates. All of them are kRPC flows or the same method as REST SSE."},
    {"id": "services", "label": "Domain services", "column": 5, "desc": "Koin singletons that hold the logic and own the tables."},
    {"id": "mirror", "label": "Mirror", "column": 5, "desc": "One server pulling the library of another."},
    {"id": "tables", "label": "Tables", "column": 6, "desc": "Where the request ends."},
]

STYLES = [
    {"kind": "always", "dash": "", "label": "request path"},
    {"kind": "optional", "dash": "dash", "label": "enabled by configuration"},
    {"kind": "push", "dash": "dot", "label": "subscription, the server streams back"},
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


node("c-apps", "Synara apps", "clients", "Desktop and mobile apps built on common-rpc. Their code lives in other repositories, only the server side is verified here.", "kRPC over WebSocket",
     [["Client plumbing", "BaseRpcServiceManager, ReconnectingRpcClient, resilientObservation"], ["Headers", "X-Api-Version, X-Ui-Schema-Version, X-Time-Zone, Accept-Language"]],
     C + "rpc/BaseRpcServiceManager.kt:225")
node("c-rest", "REST clients", "clients", "Anything calling the generated REST layer. The methods are the same as on kRPC.", "JSON over HTTP")
node("c-browser", "Browser", "clients", "Web login through the hand-written auth routes and the mirror admin page.", "cookie synara-auth")
node("c-subsonic", "Subsonic clients", "clients", "Third-party players speaking Subsonic or OpenSubsonic.", "/rest/*")
node("c-mcp", "MCP clients", "clients", "Assistants reading the listen history through seven read-only tools.", "API key, scope mcp")
node("c-radio", "Radio players", "clients", "Any player that can pull an endless AAC stream.", "API key, scope radio")
node("c-mirror", "Mirroring server", "clients", "Another Synara server that pulls this library as a kRPC client with admin credentials.", "RemoteMirrorService", None, S + "services/RemoteMirrorService.kt:856")
node("c-dev", "Dev clients", "clients", "Client builds pointed at the mock server during development.")
node("c-ops", "Probes and tooling", "clients", "Health probes and API explorers.")

node("px-proxy", "proxy", "proxy", "Public relay. A client connects to /{id}/rpc and the proxy forwards the frames into the tunnel that server {id} opened. Paths that do not start with /rpc are closed with the reason Only RPC paths are proxied.", "port 8081",
     [["Routes", "GET /instances, WS /proxy/server, WS /{id}/{...}"], ["Limits", "none: unlimited send queues, no client cap, no client auth"]], P + "Application.kt:147")
node("px-tunnel", "Server tunnel", "proxy", "The server dials out to the proxy and keeps one WebSocket open. Every client connection is multiplexed onto it as binary ProxyMessage frames.", "ReverseProxyService",
     [["Frame", "1 byte type, 16 byte clientId, 1 byte subtype, payload"], ["Types", "0 NewClient, 1 ClientFrame, 2 ClientDisconnected, 3 AssignedId, 4 Ping, 5 Pong"], ["Keepalive", "Ping every 10 s, close after 30 s of silence"], ["Reconnect", "5 s delay after an error, at once after a clean close"]],
     S + "services/ReverseProxyService.kt:122")
node("px-inproc", "In-process KrpcServer", "proxy", "For each NewClient frame the server builds a ProxyKrpcServer over a MultiplexedTransport and registers services by the forwarded path. /rpc and /rpc/auth get the public services, /rpc/services gets the authenticated ones after the forwarded bearer token is validated.", "one per proxied client",
     [["Call object", "ProxyCall, request.cookies throws and the remote host is 127.0.0.1"]], S + "services/ReverseProxyService.kt:216")

node("e-rpc-public", "WS /rpc, /rpc/auth", "entry", "kRPC over WebSocket in CBOR without authentication. Both paths register the same three public services.", "3 public services", [["Encoding", "CBOR, legacy wire below API version 8"]], S + "Routing.kt:124")
node("e-rpc-services", "WS /rpc/services", "entry", "kRPC over WebSocket in CBOR. The upgrade answers 401 without a valid token. Services are created per connection and capture the user, the client info and the session id.", "50 services, JWT",
     [["WebSocket", "ping 15 s, timeout 5 min, unlimited frame size"]], S + "Routing.kt:146")
node("e-handshake", "WS /handshake", "entry", "Sends one binary CBOR HandshakeResponse and closes. No authentication.", "versions and ssl flags", [["Fields", "secure, sslSupported, apiVersion, uiSchemaVersion"]], S + "Routing.kt:140")
node("e-rest-public", "REST public block", "entry", "The generated routes mounted under optional authentication: /serverStats/*, /auth/*, /handshake/handshake, /image/* and /animatedImage/*. The first three services need no user. Of the two image services only the imageData routes work without a user. A token is read when present.", "optional JWT", None, S + "routing/RestRegistry.kt:28")
node("e-rest-auth", "REST authenticated", "entry", "49 generated register calls, mounted at /<service> from the interface name without I and Service. Answers 401 without a user.", "49 services, JWT",
     [["Response kinds", "JSON, UNIT, BYTES, BYTE_FLOW, SSE, FILE"], ["Errors", "400, 401, 403, 404, 500. 400, 403 and 500 carry a plain text message"], ["Uploads", "ByteArray parameters are read from the JSON request body, not multipart"]], S + "routing/RestRegistry.kt:40")
node("e-rest-sse", "REST SSE", "entry", "Flow methods on REST. One JSON value per data line and a keep-alive comment every 5 s.", "Flow methods", None, S + "routing/rest/RestCall.kt:234")
node("e-rest-files", "Ranged audio files", "entry", "GET and HEAD with Accept-Ranges through respondFile: /song/streamSong/{id}, /song/downloadSong/{id}, /song/streamSongAtmos/{id}, /podcast/streamEpisode/{episodeId}. downloadSong transcodes on demand to OPUS or AAC.", "JWT header or cookie", None, S + "routing/rest/RestCall.kt:259")
node("e-rest-images", "Public image routes", "entry", "/image/imageData/{id} and /animatedImage/imageData/{id} are @RestPublic and mounted under optional authentication. /release/releaseImage/{releaseId} is @RestPublic too, but IReleaseService is registered inside the JWT authenticate block. The content type is sniffed from the magic bytes.", "@RestPublic", None, S + "routing/rest/RestCall.kt:215")
node("e-auth-rest", "Auth REST", "entry", "Hand-written JSON routes: POST /authenticate and POST /refresh-token without auth, POST /register for admins, GET /userInfo, GET /sessions and DELETE /sessions/{sessionId} with a token.", "JwtService", None, S + "services/JwtService.kt:115")
node("e-radio", "Radio routes", "entry", "GET /radio/stream, /radio/{sessionId}/stream and /radio/channel/{channelId}/stream. Endless audio/aac in ADTS with optional ICY metadata every 16000 bytes.", "API key, scope radio", None, S + "routing/RadioRoutes.kt:40")
node("e-mcp", "POST /mcp", "entry", "Stateless Streamable HTTP with JSON responses. GET and DELETE answer 405. Seven read-only listen history tools.", "API key, scope mcp", None, S + "routing/McpRoutes.kt:54")
node("e-subsonic", "Subsonic /rest/*", "entry", "32 authenticated endpoints plus getOpenSubsonicExtensions, each as GET and POST, with and without .view. Mounted by the built-in SubsonicPlugin through IRoutePlugin, outside the JWT block.", "built-in plugin", None, S + "services/subsonic/SubsonicRoutes.kt:74")
node("e-mirror-admin", "/admin/mirror", "entry", "An HTML page served without authentication that carries its own login form, and an admin API behind it: start, stop, reset, stats, remote-users, remote-playlists, remote-user-playlists, remote-instances, local-users, remote-image and an SSE progress stream.", "page public, API admin",
     [["Cache", "remote-image is cached 14 days, the admin check runs before the lookup"]], S + "routing/Mirror.kt:88")
node("e-static", "/static/*", "entry", "Static resources. The only file is mirror.js.", None, None, S + "Routing.kt:110")
node("e-docs", "/api.json, /swagger", "entry", "The OpenAPI document and its explorer.", None, None, S + "Routing.kt:112")
node("e-health", "Health", "entry", "KHealth with an available check and the readiness checks indexer_ready and transcoder_ready. The paths are the library defaults.", "KHealth", None, S + "Routing.kt:63", ["unverified"])

node("a-jwt", "JWT + session check", "auth", "Access token as HS256 with the claims usr and ses, valid 24 h. Validation also requires an active session row, so deactivating a session revokes its tokens at once. Refresh tokens are 192 random bytes, Base64 encoded and cut to 255 characters, valid 30 days.", "Bearer or cookie",
     [["Skipped for", "paths ending in /callback or containing /proxy/"]], S + "services/JwtService.kt:84")
node("a-cookie", "Cookie synara-auth", "auth", "The same access token as a cookie. When both are present the cookie wins.", None, None, S + "services/JwtService.kt:67")
node("a-apikey", "API key", "auth", "synara_<base64>, stored as a sha256 hash plus the encrypted raw key, with comma-separated scopes, expiry and revocation. Read from ?apiKey, X-API-Key or Bearer. It does not open kRPC or the generated REST layer.", "scopes radio, mcp, subsonic", None, S + "services/ApiKeyService.kt:30")
node("a-subsonic", "Subsonic credentials", "auth", "apiKey with scope subsonic, or u + t + s as md5 of secret and salt, or u + p, checked against a per-user secret.", "SubsonicAuthenticator", None, S + "services/subsonic/SubsonicAuth.kt:23")
node("a-login", "Login and refresh", "auth", "authenticate checks the BCrypt hash and creates a session row with user agent and IP. refresh issues a new pair on the same session. createDeviceSession mints a second session for another device.", "IAuthService", None, S + "services/RpcAuthService.kt:20")

node("w-compat", "withClientCompat", "chain", "Outermost on kRPC and REST. ResponseShaper walks every result, also inside flows, and applies the compat rules that are active for the caller's ClientInfo: TitleTagsCompat, ReleaseVersionsCompat, AlbumTitleTagsCompat, AlbumVersionsCompat and UiSchemaCompat.", "X-Api-Version", None, S + "utils/ClientCompat.kt:30")
node("w-metrics", "withMetrics", "chain", "Feeds RpcMetricsCollector. Present only when metrics are enabled.", "metrics.enabled", None, S + "routing/RpcRegistry.kt:59")
node("w-caching", "withCaching", "chain", "Looks for @Cached and would use the Redis key rpc_cache:<iface>:<method>:<md5(args)>. No method carries @Cached, so the layer does nothing today.", "inert", None, S + "utils/Caching.kt:49", ["dead"])
node("w-logging", "withLogging", "chain", "Logs each method call with its arguments at info level. @LogParam masks, excludes or reduces an argument. This is separate from the Ktor CallLogging plugin, which logs every HTTP request.", None, None, S + "utils/Logging.kt:111")
node("w-authz", "withAuthorization", "chain", "Reflective proxy that checks @RequiresAdmin and @RequiresCapability on the interface or implementation method and throws UnauthorizedException. Capabilities are IMPORT, EDIT, DELETE and PODCAST_EDIT, and an admin has all of them.", "permissions", [["Usage", "63 admin, 21 EDIT, 4 IMPORT, 4 PODCAST_EDIT, 3 DELETE"]], S + "utils/Authorization.kt:37")
node("w-adapter", "RPC adapter", "chain", "Innermost. A per-connection Rpc*Service or *RpcService that implements the interface and delegates to the Koin singleton with the user id. Some singletons implement the interface directly.", "per connection", None, S + "routing/RpcRegistry.kt:202")
node("w-rest-chain", "REST chain", "chain", "RestRegistry builds the same adapter with withAuthorization and restRoute wraps it in withClientCompat. The public block passes its services without withAuthorization. The withLogging, withMetrics and withCaching proxies belong to the kRPC registration. REST requests are logged by the Ktor CallLogging plugin like every HTTP request.", "compat + authorization", None, S + "routing/rest/RestCall.kt:296")

DOMAINS = [
    ("d-public", "Public", "3", "IServerStatsService, IAuthService, IHandshakeService", "Reachable without a token on /rpc and /rpc/auth.", 94),
    ("d-library", "Library", "12", "ISongService, IAlbumService, IArtistService, IPlaylistService, IUserPlaylistService, ICollectionService, IImageService, IAnimatedImageService, ILyricsService, ILyricsSearch, ITimecodeTagService, ICustomAudioService", "Songs, albums, artists, playlists, collections, images and lyrics.", 202),
    ("d-playback", "Playback and devices", "8", "IPlaybackService, IQueueService, ISessionService, IClientRequestService, IRemoteControlService, IClientSettingsService, IChangeService, IEntityChangeService", "Playback state, the shared queue, sessions, device to device requests and change delivery.", 286),
    ("d-listening", "Listening", "5", "IScrobbleService, IListeningStatsService, IRecommendationService, IListenBrainzService, IListenBackupService", "Scrobbles, statistics, recommendations and the two listen sync targets.", 404),
    ("d-radio", "Radio and podcast", "3", "IRadioService, IRadioChannelService, IPodcastService", "Radio sessions, channels and podcasts.", 320),
    ("d-discovery", "Discovery and metadata", "5", "IDiscoveryService, IReleaseService, IMusicBrainzService, IMetadataService, IAudioAnalysisService", "Discovery, the release feed and metadata lookups.", 226),
    ("d-import", "Import", "3", "IImportService, IIndexer, IFavSyncService", "Imports, indexing and favourites sync.", 192),
    ("d-ui", "UI and integrations", "3", "IUiService, ICoverGenerationService, IHueService", "Server-driven UI, generated covers and Philips Hue.", 184),
    ("d-accounts", "Accounts", "3", "IUserService, IApiKeyService, ISubsonicCredentialService", "Users, API keys and Subsonic secrets.", 195),
    ("d-admin", "Admin and ops", "8", "IBackupService, IUserPlaylistBackupService, IDbManagementService, IMirrorService, IRemoteMirrorService, IScheduledTaskLogService, IScheduledTaskConfigurationService, IRpcMetricsService", "Backups, database export and import, mirroring, scheduled tasks and RPC metrics.", 351),
]
for did, label, count, ifaces, desc, line in DOMAINS:
    node(did, label, "domains", desc, count + " interfaces", [["Interfaces", ifaces]], S + "routing/RpcRegistry.kt:%d" % line)

SERVICES = [
    ("s-auth", "Auth services", "JwtService, AuthService, UserService, SessionService, RefreshTokenService, ApiKeyService and ApiKeyScopeRegistry from authModule. ServerStatsService from systemModule. HandshakeService is built per call", "services/AuthModule.kt:6"),
    ("s-library", "Library services", "SongService, AlbumService, ArtistService, GenreService, PlaylistService, UserPlaylistService, CollectionService, ImageService, AnimatedImageService, LyricsService, LyricsSearch, TimecodeTagService, CustomAudioService", "services/LibraryModule.kt:9"),
    ("s-listening", "Listening services", "ListenService, ScrobbleService, ListeningStatsService, PlaybackService, QueueService, RemoteControlService, ClientSettingsService, ClientRequestService, RadioService, RadioChannelService, DiscoveryService, RecommendationServingService", "services/ListeningModule.kt:6"),
    ("s-metadata", "Metadata and release", "MetadataDispatcherService, CachedMusicBrainzService and MetadataFetchingService from metadataModule. ReleaseService and AppleMusicReleaseService from releaseModule. AudioAnalysisService from audioModule", "services/metadata/MetadataModule.kt:8"),
    ("s-import", "Import services", "ImportService and ImporterProxy from importModule. Indexer and FavSyncService from libraryModule. JobService and IntakeService from intakeModule", "services/import/ImportModule.kt:8"),
    ("s-podcast", "Podcast services", "PodcastService, PodcastFeedService, PodcastImportService, PodcastStreamService, PodcastIndexService", "services/podcast/PodcastModule.kt:6"),
    ("s-ui", "UI, cover and Hue", "UiService and UiRegistry from uiModule. CoverGenerationService from coverModule. HueService from hueModule", "services/ui/UiModule.kt:6"),
    ("s-sync", "Sync services", "ListenBrainzService, ListenBackupService", "services/sync/SyncModule.kt:6"),
    ("s-system", "System services", "BackupService, UserPlaylistBackupService, DbManagementService, MirrorService, RemoteMirrorService and RpcMetricsService from systemModule. ScheduledTaskLogService and ScheduledTaskConfigurationService from scheduleModule. EntityChangeService from libraryModule. ChangeNotifier from coreModule", "services/SystemModule.kt:11"),
    ("s-subsonic", "SubsonicQueryService", "SubsonicQueryService and SubsonicAuthenticator come from the plugin Koin module. The Subsonic routes also inject SongService, AlbumService and ArtistService directly, so none of the RPC wrappers runs on this path.", "services/subsonic/SubsonicPlugin.kt:15"),
    ("s-mcp", "ListenHistoryQueryService", "Backs the MCP tools with SongService, ArtistService, AlbumService and ScrobbleService.", "mcp/ListenHistoryQueryService.kt:51"),
    ("s-radio-stream", "Radio streaming", "RadioService, RadioChannelService, SongService and Transcoder produce the endless AAC stream.", "routing/RadioRoutes.kt:35"),
]
for sid, label, classes, src in SERVICES:
    if sid in ("s-subsonic", "s-mcp", "s-radio-stream"):
        node(sid, label, "services", classes, None, None, S + src)
    else:
        node(sid, label, "services", "Koin singletons of this domain. They run their queries in dbQuery transactions.", "Koin singletons", [["Classes", classes]], S + src)

node("p-changes", "Change topics", "push", "IChangeService.observeChanges. Per-user topics ONLINE_DEVICES, HOME_CARDS, LISTENS and LISTENBRAINZ_STATUS, coalesced over 100 ms with a buffer of 64 that drops the oldest. The client re-reads the affected list.", "ChangeNotifier", None, S + "core/ChangeNotifier.kt:17")
node("p-entity", "Entity change pulls", "push", "IEntityChangeService, API version 10. A pull of library changes since a timestamp: getWindow, allChanges, byArtist, byAlbum, byPlaylist, byCollection. Changes are kept for ENTITY_CHANGE_RETENTION_DAYS, 30 by default.", "since a timestamp", None, S + "services/RpcEntityChangeService.kt:9")
node("p-queue", "Queue sync", "push", "IQueueService.observeQueue delivers a QueueInfo after each accepted write, without an initial snapshot. Writes carry a baseVersion and conflict when it is stale.", "versioned queue", [["Tables", "userQueue, userQueueEntry, queueSyncDevice"]], S + "services/QueueService.kt:86")
node("p-requests", "Client requests", "push", "IClientRequestService.connect opens a per-session request channel that carries UploadQueue and ControlPlayback. A device counts as present while the stream is open. requestUploadFrom waits 30 s for the other device.", "device to device", None, S + "services/ClientRequestService.kt:42")
node("p-remote", "Remote control", "push", "IRemoteControlService.observeStatus replays the last status. sendCommand waits up to 10 s for the acknowledgement.", "commands and status", None, S + "services/RemoteControlService.kt:21")
node("p-playback", "Playback state", "push", "IPlaybackService.observePlaybackState delivers every PlaybackState reported for a session from the moment of subscribing. The last state is kept for getPlaybackState.", "per session", None, S + "services/PlaybackService.kt:36")
node("p-nowplaying", "Now playing", "push", "IScrobbleService writes notify the LISTENS topic and emit NowPlayingChanged.", "ScrobbleService", None, S + "services/ScrobbleService.kt:45")
node("p-settings", "Client settings", "push", "IClientSettingsService.observeSettings.", None, None, S + "services/ClientSettingsService.kt:64")
node("p-ui", "Server-driven UI", "push", "IUiService.subscribe and subscribeWithContext deliver UiRender trees, subscribeLive delivers UiLiveUpdate.", "X-Ui-Schema-Version", None, C + "services/IUiService.kt:49")
node("p-other", "Other observe flows", "push", "IPodcastService.observeProgress, IListenBackupService.observeState, IScheduledTaskLogService.observeGroupedLogs, IScheduledTaskConfigurationService.observeConfigurations, IImportService.logs, IIndexer.start, IRemoteMirrorService.getActiveMirrorProgress, IHueService.startPairing, IRadioService.observeRadio.", "progress and state")

node("m-source", "IMirrorService", "mirror", "Source side, on the normal /rpc/services. Every method needs an admin. It serves server paths, flows of songs, artists, aliases, albums, playlists, image metadata, users and password hashes, and getSongData.", "source side, admin only",
     [["getSongData", "quality -1 sends the original file, otherwise the transcoded download"]], S + "services/MirrorService.kt:62")
node("m-target", "RemoteMirrorService", "mirror", "Target side. It owns a kRPC client per host, port, user and proxy instance, logs in with the remote admin's name and password and keeps no refresh token. One mirror runs at a time.", "target side",
     [["Modes", "mirror keeps remote UUIDs, isImport merges by hash, name or metadata with new ids"], ["Control", "IRemoteMirrorService, /admin/mirror, SETUP_FROM_MIRROR_*"]], S + "services/RemoteMirrorService.kt:126")
node("m-remote", "Remote Synara server", "mirror", "The server being copied, reached directly or through its proxy as /{instanceId}.", "source of the pull")

node("t-user", "user, user_capability", "tables", "Accounts and their capabilities. The first admin is seeded from CLIENT_ID and CLIENT_SECRET.", None, None, S + "db/UserTable.kt:6")
node("t-session", "session, refreshToken", "tables", "Sessions with lastActive. Inactive sessions and sessions unused for 30 days are removed by cleanupOldSessions. Refresh tokens are stored in the column tokenHash.", None, None, S + "db/SessionTable.kt:7")
node("t-apikey", "apiKey", "tables", "API keys with scopes, expiry, revocation and last use.", None, None, S + "db/ApiKeyTable.kt:7")
node("t-subsonic", "subsonicCredential", "tables", "Per-user Subsonic secrets.", None, None, S + "db/SubsonicCredentialTable.kt:7")
node("t-db", "PostgreSQL / SQLite", "tables", "All other tables. Queries run in dbQuery, a suspended Exposed transaction on the IO dispatcher.", "dbQuery", None, S + "core/db/DbQuery.kt:7")

node("k-mock", "mock-server", "mock", "Standalone Ktor app that registers a reflection mock for every interface in the generated service registry. IUiService has an explicit MockUiService. Any Bearer header is accepted.", "port 8081",
     [["kRPC", "/rpc, /rpc/auth, /rpc/services"], ["REST", "/<service>/<method> answers generated dummy JSON. Only the explicit MockUiService is called, with String query parameters, and a Flow from it returns its first item"], ["Missing", "/handshake, legacy wire, file streaming, SSE, radio, MCP, Subsonic, proxy"]], M + "Main.kt:43")

edge("c-apps", "e-handshake", "first contact", "always",
     flow("Handshake", "WS /handshake, or the handshake method on kRPC or REST", "secure, sslSupported, apiVersion and uiSchemaVersion", S + "Routing.kt:140", "first contact with a server"))
edge("c-apps", "e-rpc-public", "kRPC", "always",
     flow("Public calls", "kRPC over WS /rpc and /rpc/auth in CBOR", "server stats, health, proxy info, login, refresh and device sessions", S + "Routing.kt:124", "connect or login"),
     flow("Server validation", "validateServer tries wss then ws and calls health()", "reachability within 5 s", C + "rpc/BaseRpcServiceManager.kt:225", "adding a server"))
edge("c-apps", "e-rpc-services", "kRPC + Bearer", "always",
     flow("Service calls and flows", "kRPC over WS /rpc/services in CBOR with a Bearer token", "calls and server-streaming flows of 50 services", S + "Routing.kt:146", "after login"),
     flow("Audio over RPC", "ISongService.streamSong, downloadSong, streamSongAtmos and IPodcastService.streamEpisode as Flow<ByteArray>", "audio chunks", C + "services/ISongService.kt:182", "playback over RPC or through the proxy"),
     flow("Token refresh", "BaseRpcServiceManager refreshes an expired token before connecting and forces one refresh when the connect fails with an authentication error", "a new access and refresh token", C + "rpc/BaseRpcServiceManager.kt:261", "connect"))
edge("c-apps", "e-rest-files", "audio", "always",
     flow("Ranged playback", "HTTP GET and HEAD with Accept-Ranges", "audio files and on-demand transcodes. The route is what the server offers. Which app uses it is decided in the app repositories and was not checked here", S + "routing/rest/RestCall.kt:259", "playback"))
edge("c-apps", "e-rest-images", "covers", "always",
     flow("Covers", "HTTP GET /image/imageData/{id}?size", "image bytes. The route is what the server offers. Which app uses it is decided in the app repositories and was not checked here", S + "routing/rest/RestCall.kt:215", "rendering lists and detail pages"))
edge("c-apps", "px-proxy", "kRPC via proxy", "optional",
     flow("Proxied kRPC", "WS /{id}/rpc, /{id}/rpc/auth, /{id}/rpc/services", "kRPC frames only. REST, /handshake, images, radio, Subsonic and MCP are not relayed", P + "Application.kt:147", "client configured with the proxy base ws(s)://host:port/<id>", "tunnel connected"),
     flow("Instance list", "HTTP GET /instances", "id and name of every connected server, without authentication", P + "Application.kt:73", "instance picker"))
edge("c-rest", "e-rest-public", "REST", "always",
     flow("Public REST", "HTTP JSON on /serverStats/*, /auth/*, /handshake/handshake", "stats, proxy info, login", S + "routing/RestRegistry.kt:28", "per request"))
edge("c-rest", "e-rest-auth", "REST + JWT", "always",
     flow("Service methods", "HTTP JSON on /<service>/<method>", "the same methods as kRPC", S + "routing/RestRegistry.kt:40", "per request"),
     flow("Uploads", "POST /image/image, /image/batch, /animatedImage/animatedImage, /customAudio/uploadCustomAudio with a JSON body", "image and audio bytes as body parameters", "docs/REST_API.md:175", "user upload"))
edge("c-rest", "e-rest-sse", "SSE", "push",
     flow("Flow methods as SSE", "text/event-stream, one JSON per data line, keep-alive comment every 5 s", "the items of a Flow method", S + "routing/rest/RestCall.kt:234", "subscribe"))
edge("c-rest", "e-rest-files", "audio", "always",
     flow("Ranged files", "HTTP GET and HEAD with Range", "audio files", S + "routing/rest/RestCall.kt:259", "playback or download"))
edge("c-rest", "e-rest-images", "images", "always",
     flow("Public images", "HTTP GET", "covers and animated covers without a token. Release images sit inside the JWT authenticate block", S + "routing/rest/RestCall.kt:215", "per request"))
edge("c-browser", "e-auth-rest", "JSON", "always",
     flow("Web login", "POST /authenticate, POST /refresh-token, GET /userInfo, GET /sessions, DELETE /sessions/{sessionId}", "tokens and the session list. The routes answer JSON and set no cookie. mirror.js writes the token into the cookie synara-auth in the browser", S + "services/JwtService.kt:115", "login and session management"),
     flow("Register a user", "POST /register", "a new account", S + "services/JwtService.kt:201", "admin action"))
edge("c-browser", "e-mirror-admin", "HTML + API", "always",
     flow("Mirror page", "GET /admin/mirror and /static/mirror.js", "the page, which loads Tailwind from a CDN in the browser", S + "routing/Mirror.kt:88", "admin opens the page"),
     flow("Mirror control", "POST /admin/mirror/{start,stop,reset,stats,remote-users,remote-playlists,remote-user-playlists,remote-instances}, GET local-users, GET remote-image/{imageId}", "mirror settings, remote listings and remote covers", S + "routing/Mirror.kt:691", "admin action"),
     flow("Mirror progress", "SSE /admin/mirror/progress", "the progress state", S + "routing/Mirror.kt:824", "page open"))
edge("c-browser", "e-static", "static", "always",
     flow("Static files", "GET /static/*", "mirror.js", S + "Routing.kt:110", "mirror page"))
edge("c-subsonic", "e-subsonic", "Subsonic", "always",
     flow("Subsonic API", "HTTP GET or POST /rest/<name>[.view]", "Subsonic XML or JSON, streams and cover art", S + "services/subsonic/SubsonicRoutes.kt:74", "per request"))
edge("c-mcp", "e-mcp", "JSON-RPC", "always",
     flow("MCP tools", "POST /mcp, stateless Streamable HTTP", "seven read-only listen history tools", S + "routing/McpRoutes.kt:54", "tool call"))
edge("c-radio", "e-radio", "AAC stream", "always",
     flow("Radio stream", "HTTP GET /radio/**", "endless audio/aac with optional ICY metadata", S + "routing/RadioRoutes.kt:40", "player connects"))
edge("c-ops", "e-health", "probe", "always",
     flow("Health and readiness", "HTTP GET on the KHealth paths", "available, indexer_ready, transcoder_ready", S + "Routing.kt:63", "probe"))
edge("c-ops", "e-docs", "OpenAPI", "always",
     flow("API description", "GET /api.json and /swagger", "the OpenAPI document", S + "Routing.kt:112", "on demand"))
edge("c-dev", "k-mock", "kRPC + REST", "always",
     flow("Mocked API", "kRPC and REST on port 8081", "generated dummy data for every interface", M + "Main.kt:43", "client development", "PORT"))
edge("c-mirror", "px-proxy", "via proxy", "optional",
     flow("Instance list for a mirror", "HTTP GET http(s)://host:port/instances", "connected server ids", S + "services/RemoteMirrorService.kt:211", "mirror setup with useProxy"))

edge("px-tunnel", "px-proxy", "tunnel", "optional",
     flow("Tunnel", "WS /proxy/server?id&name&key. The proxy also accepts the key in the header X-Proxy-Key", "multiplexed ProxyMessage frames", S + "services/ReverseProxyService.kt:122", "ReverseProxyWorker, cron 0 * * * *, is the only caller of startService", "PROXY_HOSTNAME, PROXY_CONTROL_PORT, PROXY_SSL, PROXY_ID, PROXY_NAME, PROXY_KEY"),
     flow("Keepalive", "protocol Ping every 10 s, answered with Pong", "liveness. The server closes after 30 s without a frame", S + "services/ReverseProxyService.kt:142", "while connected"),
     flow("Frame format", "one binary WebSocket frame per ProxyMessage", "type, clientId, subtype and payload", CP + "Protocol.kt:63"))
edge("px-proxy", "px-inproc", "NewClient", "optional",
     flow("New client", "ProxyMessage type 0 with JSON {uri, headers}", "the client's path and every request header", S + "services/ReverseProxyService.kt:161", "a client connects to /{id}/..."),
     flow("Client frames", "ProxyMessage type 1, subtype 1 binary or 0 text", "kRPC frames in both directions", CP + "Protocol.kt:119", "while the client is connected"))
edge("px-inproc", "a-jwt", "bearer", "optional",
     flow("Forwarded token", "the Authorization header from the NewClient frame", "the bearer token, validated before authenticated services are registered", S + "services/ReverseProxyService.kt:216", "path /rpc/services"))
edge("px-inproc", "w-compat", "services", "optional",
     flow("Same services, same chain", "RpcServer variant of the registrar", "public or authenticated services by forwarded path", S + "routing/RpcRegistry.kt:120", "per proxied client"))

edge("e-rpc-public", "w-compat", "register", "always",
     flow("Public registration", "registerService per connection", "IServerStatsService, IAuthService, IHandshakeService", S + "routing/RpcRegistry.kt:87", "connection opened"))
edge("e-rpc-services", "a-jwt", "authenticate", "always",
     flow("Upgrade check", "Ktor authenticate block", "the access token. 401 on upgrade when it is missing or the session is inactive", S + "Routing.kt:146", "connection opened"))
edge("e-rpc-services", "w-compat", "register", "always",
     flow("Authenticated registration", "registerService per connection through a local ServiceRegistrar", "50 factories that capture user, clientInfo and sessionId", S + "routing/RpcRegistry.kt:107", "connection opened"))
edge("e-rest-auth", "a-jwt", "authenticate", "always",
     flow("User required", "restRoute with requireUser", "401 when no user resolves", S + "routing/rest/RestCall.kt:290", "per request"))
edge("e-rest-public", "a-jwt", "optional token", "always",
     flow("Optional user", "optional authentication on the public block", "a user when a token is sent", S + "Routing.kt:136", "per request"))
edge("e-rest-files", "a-jwt", "authenticate", "always",
     flow("Header or cookie", "Authorization header or the cookie synara-auth", "the access token", S + "services/JwtService.kt:67", "per request"))
edge("e-rest-auth", "w-rest-chain", "restRoute", "always",
     flow("Generated route", "register<Iface>Rest calls restRoute", "the adapter with withAuthorization, wrapped in withClientCompat", S + "routing/rest/RestCall.kt:284", "per request"))
edge("e-rest-public", "w-rest-chain", "restRoute", "always",
     flow("Generated public route", "restRoute with requireUser false, for the image services only on their @RestPublic routes", "the service wrapped in withClientCompat. The public block adds no withAuthorization", S + "routing/rest/RestCall.kt:284", "per request"))
edge("e-rest-sse", "w-rest-chain", "restRoute", "always",
     flow("Flow as SSE", "restRoute with the SSE response kind", "flow items", S + "routing/rest/RestCall.kt:234", "subscribe"))
edge("e-rest-files", "w-rest-chain", "restRoute", "always",
     flow("File provider", "restRoute unwraps the proxy target as RestFileProvider", "a file answered with respondFile", S + "routing/rest/RestCall.kt:259", "per request"))
edge("e-rest-images", "w-rest-chain", "restRoute", "always",
     flow("Image bytes", "restRoute with the BYTES response kind", "image bytes with a sniffed content type", S + "routing/rest/RestCall.kt:215", "per request"))
edge("e-auth-rest", "a-login", "login", "always",
     flow("Hand-written login", "POST /authenticate and /refresh-token", "name and password, or a refresh token", S + "services/JwtService.kt:130", "web login"))
edge("e-handshake", "s-auth", "HandshakeService", "always",
     flow("Handshake response", "HandshakeService", "version constants. secure honours X-Forwarded-Proto", S + "services/HandshakeService.kt:15", "connection opened"))
edge("e-radio", "a-apikey", "scope radio", "always",
     flow("Radio key", "API key from ?apiKey, X-API-Key or Bearer", "a key with scope radio", S + "routing/RadioRoutes.kt:64", "per request"))
edge("e-mcp", "a-apikey", "scope mcp", "always",
     flow("MCP key", "API key from ?apiKey, X-API-Key or Bearer", "a key with scope mcp", S + "routing/McpRoutes.kt:97", "per request"))
edge("e-subsonic", "a-subsonic", "authenticate", "always",
     flow("Subsonic auth", "query parameters apiKey, or u + t + s, or u + p", "the user", S + "services/subsonic/SubsonicAuth.kt:23", "per request"))
edge("e-mirror-admin", "a-cookie", "cookie", "always",
     flow("Admin API", "cookie synara-auth plus an admin check", "the admin user", S + "routing/Mirror.kt:658", "per request"))
edge("e-radio", "s-radio-stream", "stream", "always",
     flow("Stream assembly", "RadioService, RadioChannelService, SongService, Transcoder", "transcoded AAC written through temp files radio_*.aac", S + "routing/RadioRoutes.kt:190", "player connected"))
edge("e-mcp", "s-mcp", "tools", "always",
     flow("Tool execution", "ListenHistoryMcpServerFactory", "listen history queries", S + "mcp/ListenHistoryMcpServer.kt:187", "tool call"))
edge("e-subsonic", "s-subsonic", "queries", "always",
     flow("Subsonic queries", "SubsonicQueryService", "library reads and likes, without the wrapper chain", S + "services/subsonic/SubsonicRoutes.kt:128", "per request"))
edge("e-mirror-admin", "m-target", "control", "always",
     flow("Mirror control", "direct calls from the route handlers", "start, stop, reset, listings", S + "routing/Mirror.kt:691", "admin action"))

edge("a-cookie", "a-jwt", "same token", "always",
     flow("Cookie transport", "cookie synara-auth", "the access token. The cookie wins over the header", S + "services/JwtService.kt:67", "per request"))
edge("a-jwt", "t-session", "session check", "always",
     flow("Active session", "validation reads the session row", "the ses claim. getUser() launches an update of lastActive on each call", S + "services/JwtService.kt:90", "per request"))
edge("a-jwt", "t-user", "load user", "always",
     flow("User lookup", "getUser()", "the usr claim resolved to a user with capabilities", S + "core/Call.kt:36", "per request"))
edge("a-login", "t-user", "BCrypt check", "always",
     flow("Password check", "BCrypt against the stored hash", "user name and password", S + "services/JwtService.kt:142", "login"))
edge("a-login", "t-session", "create", "always",
     flow("Session and tokens", "insert session, issue access and refresh token", "user agent, IP, a 24 h access token and a 30 day refresh token", S + "services/JwtService.kt:281", "login, refresh, createDeviceSession"))
edge("a-apikey", "t-apikey", "hash lookup", "always",
     flow("Key lookup", "sha256 of the presented key", "scopes, expiry, revocation. lastUsed is updated", S + "services/ApiKeyService.kt:82", "per request"))
edge("a-subsonic", "t-subsonic", "secret", "always",
     flow("Secret lookup", "per-user secret", "the secret that t and p are checked against", S + "services/subsonic/SubsonicAuth.kt:44", "per request"))
edge("a-subsonic", "a-apikey", "scope subsonic", "always",
     flow("Key as Subsonic login", "apiKey parameter", "an API key with scope subsonic", S + "services/subsonic/SubsonicAuth.kt:34", "per request"))

edge("w-compat", "w-metrics", "wraps", "always",
     flow("Wrapper order", "dynamic proxy", "the call. Results are shaped on the way back", S + "routing/RpcRegistry.kt:60"))
edge("w-metrics", "w-caching", "wraps", "always",
     flow("Wrapper order", "dynamic proxy", "the call. Skipped when metrics are disabled", S + "routing/RpcRegistry.kt:59"))
edge("w-caching", "w-logging", "wraps", "always",
     flow("Wrapper order", "dynamic proxy", "the call. No method is cached today", S + "routing/RpcRegistry.kt:58"))
edge("w-logging", "w-authz", "wraps", "always",
     flow("Wrapper order", "dynamic proxy", "the call", S + "routing/RpcRegistry.kt:190"))
edge("w-authz", "w-adapter", "wraps", "always",
     flow("Permission check", "dynamic proxy", "the call, after @RequiresAdmin and @RequiresCapability pass", S + "utils/Authorization.kt:37"))
edge("w-rest-chain", "w-authz", "wraps", "always",
     flow("REST order", "withClientCompat around withAuthorization around the adapter", "the call. The RPC-level logging, metrics and caching proxies are not part of this path", S + "routing/rest/RestCall.kt:296"))
for did, label, count, ifaces, desc, line in DOMAINS:
    edge("w-adapter", did, "implements", "always",
         flow(label + " adapters", "registrar.register per interface", ifaces, S + "routing/RpcRegistry.kt:%d" % line, "per connection"))

DOMAIN_SERVICES = [
    ("d-public", "s-auth", "AuthService, JwtService, ServerStatsService and HandshakeService", "services/RpcAuthService.kt:20"),
    ("d-accounts", "s-auth", "UserService, ApiKeyService and SubsonicCredentialService", "services/ApiKeyService.kt:30"),
    ("d-library", "s-library", "the library singletons, called with the user id", "services/LibraryModule.kt:9"),
    ("d-playback", "s-listening", "PlaybackService, QueueService, RemoteControlService, ClientSettingsService and ClientRequestService", "services/ListeningModule.kt:6"),
    ("d-playback", "s-auth", "SessionService", "services/SessionService.kt:66"),
    ("d-playback", "s-system", "EntityChangeService and ChangeNotifier", "services/RpcEntityChangeService.kt:9"),
    ("d-listening", "s-listening", "ScrobbleService, ListeningStatsService and RecommendationServingService", "services/ListeningModule.kt:6"),
    ("d-listening", "s-sync", "ListenBrainzService and ListenBackupService", "services/sync/SyncModule.kt:6"),
    ("d-radio", "s-listening", "RadioService and RadioChannelService", "services/ListeningModule.kt:6"),
    ("d-radio", "s-podcast", "PodcastService through RpcPodcastService", "services/podcast/RpcPodcastService.kt:32"),
    ("d-discovery", "s-metadata", "MetadataDispatcherService, CachedMusicBrainzService, ReleaseService and AudioAnalysisService", "services/metadata/MetadataModule.kt:10"),
    ("d-discovery", "s-listening", "DiscoveryService", "services/DiscoveryService.kt:20"),
    ("d-import", "s-import", "ImportService, Indexer and FavSyncService", "services/import/ImportService.kt:67"),
    ("d-ui", "s-ui", "UiService, CoverGenerationService and HueService", "services/ui/UiService.kt:100"),
    ("d-admin", "s-system", "BackupService, DbManagementService, MirrorService, RemoteMirrorService and the scheduled task services", "services/SystemModule.kt:11"),
]
for did, sid, what, src in DOMAIN_SERVICES:
    edge(did, sid, "delegates", "always", flow("Delegation", "direct call from the adapter", what, S + src, "per call"))

for sid, src in [("s-auth", "services/AuthModule.kt:6"), ("s-library", "services/LibraryModule.kt:9"), ("s-listening", "services/ListeningModule.kt:6"), ("s-metadata", "services/metadata/MetadataModule.kt:8"),
                 ("s-import", "services/import/ImportModule.kt:8"), ("s-podcast", "services/podcast/PodcastModule.kt:6"), ("s-ui", "services/ui/UiModule.kt:6"), ("s-sync", "services/sync/SyncModule.kt:6"),
                 ("s-system", "services/SystemModule.kt:11"), ("s-subsonic", "services/subsonic/SubsonicRoutes.kt:128"), ("s-mcp", "mcp/ListenHistoryQueryService.kt:51"), ("s-radio-stream", "routing/RadioRoutes.kt:35")]:
    edge(sid, "t-db", "dbQuery", "always", flow("Queries", "Exposed in dbQuery", "reads and writes of the domain's tables", S + "core/db/DbQuery.kt:7", "per call"))

PUSH = [
    ("p-changes", "observeChanges", "IChangeService.observeChanges as a kRPC flow or SSE", "topic pings: ONLINE_DEVICES, HOME_CARDS, LISTENS, LISTENBRAINZ_STATUS", "services/RpcChangeService.kt:12", "a write that calls ChangeNotifier.notify"),
    ("p-entity", "changes since T", "IEntityChangeService.getWindow, allChanges, byArtist, byAlbum, byPlaylist, byCollection", "finite flows of EntityChange since a timestamp", "services/RpcEntityChangeService.kt:15", "client sync, API version 10"),
    ("p-queue", "observeQueue", "IQueueService writes and observeQueue", "QueueInfo after each accepted write", "services/QueueService.kt:132", "queue edit on any device"),
    ("p-requests", "connect", "IClientRequestService.connect, observeRequests, complete", "UploadQueue and ControlPlayback requests from another device", "services/ClientRequestService.kt:55", "another device asks"),
    ("p-remote", "observeStatus", "IRemoteControlService.sendCommand, reportStatus, observeStatus", "playback commands and status", "services/RemoteControlService.kt:51", "user action on the controlling device"),
    ("p-playback", "observe state", "IPlaybackService.observePlaybackState(sessionId)", "each reported PlaybackState", "services/PlaybackService.kt:36", "state change"),
    ("p-nowplaying", "nowPlaying", "IScrobbleService.nowPlaying, reportPlayback, clearNowPlaying, listened", "now playing and listens", "services/ScrobbleService.kt:50", "playback"),
    ("p-settings", "observeSettings", "IClientSettingsService.observeSettings", "synced client settings", "services/ClientSettingsService.kt:64", "a setting changes"),
    ("p-ui", "subscribe", "IUiService.subscribe, subscribeWithContext, subscribeLive", "UiRender trees and UiLiveUpdate", C + "services/IUiService.kt:49", "page open"),
    ("p-other", "observe", "observeProgress, observeState, observeGroupedLogs, observeConfigurations, logs, start, getActiveMirrorProgress, startPairing, observeRadio", "progress and state streams", "services/sync/ListenBackupService.kt:57", "subscribe"),
]
for pid, label, transport, carries, src, trigger in PUSH:
    edge("e-rpc-services", pid, label, "push", flow("Subscription", transport, carries, src if src.startswith("common-rpc") else S + src, trigger))
edge("e-rest-sse", "p-changes", "SSE", "push",
     flow("Same flows over SSE", "REST SSE", "the Flow methods of the REST-registered interfaces answer as SSE, IChangeService.observeChanges among them", S + "routing/rest/RestCall.kt:234", "subscribe"))
edge("p-nowplaying", "p-changes", "LISTENS", "always",
     flow("Listens topic", "ChangeNotifier.notify(userId, LISTENS)", "a ping that makes clients re-read recent listens", S + "services/ScrobbleService.kt:45", "now playing or listen written"))
edge("p-changes", "s-system", "ChangeNotifier", "always",
     flow("In-memory notifier", "ChangeNotifier, single instance", "per-user topics with 100 ms coalescing", S + "core/ChangeNotifier.kt:17", "notify"))
edge("p-entity", "s-system", "change log", "always",
     flow("Change log read", "EntityChangeService by keyset", "entity_change and user_entity_change rows", S + "services/EntityChangeService.kt:100", "pull"))
edge("p-queue", "s-listening", "QueueService", "always",
     flow("Queue writes", "QueueService", "userQueue, userQueueEntry, queueSyncDevice", S + "services/QueueService.kt:86", "queue edit"))
edge("p-requests", "s-listening", "request channel", "always",
     flow("Request channel", "ClientRequestService, in memory", "per-session channels", S + "services/ClientRequestService.kt:107", "connect"))
edge("p-remote", "s-listening", "command relay", "always",
     flow("Command relay", "RemoteControlService, in memory", "commands with a 10 s acknowledgement timeout", S + "services/RemoteControlService.kt:56", "sendCommand"))
edge("p-playback", "s-listening", "PlaybackService", "always",
     flow("State store", "PlaybackService, in memory", "the last PlaybackState per session", S + "services/PlaybackService.kt:12", "report"))
edge("p-nowplaying", "s-listening", "ScrobbleService", "always",
     flow("Scrobbles", "ScrobbleService and ListenService.ingestLocal", "listen rows with source LOCAL", S + "services/ListenService.kt:134", "listened"))
edge("p-settings", "s-listening", "settings store", "always",
     flow("Settings store", "ClientSettingsService", "clientsetting rows and their history", S + "services/ClientSettingsService.kt:64", "write"))
edge("p-ui", "s-ui", "UiService", "always",
     flow("UI rendering", "UiService with the UiRegistry", "contributions filtered by access.allows(user)", S + "services/ui/UiService.kt:100", "render, invoke"))

edge("c-mirror", "e-rpc-public", "login", "always",
     flow("Mirror login", "kRPC /rpc/auth authenticate", "the remote admin's name and password. No refresh token is kept", S + "services/RemoteMirrorService.kt:905", "mirror start"))
edge("c-mirror", "e-rpc-services", "IMirrorService", "always",
     flow("Mirror pull", "kRPC /rpc/services, CBOR, WebSocket ping 15 s", "metadata flows, image bytes and audio chunks", S + "services/RemoteMirrorService.kt:231", "mirror running"))
edge("d-admin", "m-source", "IMirrorService", "always",
     flow("Source side", "MirrorService behind @RequiresAdmin", "everything a mirror may read", S + "services/MirrorService.kt:62", "a mirroring server calls"))
edge("m-source", "t-db", "reads", "always",
     flow("Library export", "flows read from the tables", "songs, artists, aliases, albums, playlists, image metadata, users and password hashes", S + "services/MirrorService.kt:87", "per mirror call"))
edge("m-target", "m-remote", "kRPC pull", "optional",
     flow("1. Stats and paths", "IServerStatsService.getStats and IMirrorService.getServerPaths", "counts and the remote directory layout", S + "services/RemoteMirrorService.kt:228", "mirror start"),
     flow("2. Selection analysis", "getSongsByPlaylist, getSongsByUserPlaylist and getLikedSongs", "the songs of the chosen playlists, user playlists and liked-by users", S + "services/RemoteMirrorService.kt:276", "when a selection is set"),
     flow("3. Users", "getUsers, then getUserPasswordHashes", "accounts", S + "services/RemoteMirrorService.kt:695", "only with importUsers"),
     flow("4. Images", "image metadata, then IImageService.getImageData", "image rows and bytes", S + "services/RemoteMirrorService.kt:354"),
     flow("5. Artists and aliases", "artist, alias and split alias flows", "artist rows", S + "services/RemoteMirrorService.kt:374"),
     flow("6. Albums, then songs", "album and song flows, then getSongData in 64 KB chunks", "rows and audio files, 3 downloads in parallel, written to a path remapped from the remote layout. Complete files are skipped", S + "services/RemoteMirrorService.kt:556"),
     flow("7. Playlists and likes", "playlist, user playlist and like flows", "playlists and likes, remapped to targetUserId when set", S + "services/RemoteMirrorService.kt:738"),
     flow("Through a proxy", "base URL with /<instanceId>", "the same calls through the remote server's proxy", S + "services/RemoteMirrorService.kt:862", None, "useProxy and proxyInstanceId"),
     flow("Setup at boot", "blocking mirror with isImport and importUsers, then halt(0)", "the whole library into an empty database", S + "Application.kt:151", "startup with 0 songs and at most 1 user", "SETUP_FROM_MIRROR_URL, SETUP_FROM_MIRROR_USERNAME, SETUP_FROM_MIRROR_PASSWORD"))
edge("m-target", "t-db", "writes", "always",
     flow("Library import", "inserts and merges", "the mirrored rows. Mirror mode keeps remote UUIDs, import mode creates new ids", S + "services/RemoteMirrorService.kt:345", "mirror running"))
edge("d-admin", "m-target", "mirror control", "always",
     flow("Mirror control over RPC", "IRemoteMirrorService and getActiveMirrorProgress", "start, stop and a progress flow", S + "services/RemoteMirrorService.kt:147", "admin action"))
edge("d-public", "px-tunnel", "getProxyInfo", "optional",
     flow("Proxy address for clients", "IServerStatsService.getProxyInfo or GET /serverStats/proxyInfo", "host, controlPort, ssl and id. The client then uses ws(s)://host:port/<id>", S + "services/ServerStatsService.kt:130", "client asks", "tunnel configured"))

note("Token check is skipped for two path patterns", "The JWT provider has a skipWhen for every path that ends in /callback or contains /proxy/.", S + "services/JwtService.kt:78")
note("checkSslSupport sends a plain GET to /handshake", "BaseRpcServiceManager.checkSslSupport requests <base>/handshake over HTTP and decodes a HandshakeResponse. The server defines /handshake as a WebSocket route and the generated REST route is /handshake/handshake. The client treats every error that is not an SSL error as success.", C + "rpc/BaseRpcServiceManager.kt:204")
note("Calls through the proxy carry a stand-in call object", "ProxyCall, the ApplicationCall built for a proxied client, throws on request.cookies and reports 127.0.0.1 as the remote host. createDeviceSession records call.request.origin.remoteHost and reads the cookie when no user is resolved first. The effect on proxied device sessions was read from code and not run.", S + "services/ReverseProxyService.kt:298", ["unverified"])
note("The tunnel is started by its worker", "ReverseProxyService.startService is called from ReverseProxyWorker, cron 0 * * * *, and from nowhere else. The service is not in the start list of Services.kt.", S + "services/schedule/ReverseProxyWorker.kt:23")
note("Refresh tokens are compared as stored", "JwtService passes the generated refresh token to RefreshTokenService.createToken, which writes it into the column tokenHash unchanged. validByTokenHash compares the presented token with that column directly.", S + "services/RefreshTokenService.kt:81")
note("Server statistics are in the public block", "IServerStatsService is registered in the public REST block and on the public kRPC paths. getStats includes the operating system name, version and architecture.", S + "services/ServerStatsService.kt:95")
note("Two login routes", "The generated POST /auth/authenticate takes username and password as query parameters. The hand-written POST /authenticate takes them as JSON, and docs/AUTHENTICATION.md recommends it for that reason.", "docs/AUTHENTICATION.md:31")
note("The proxy key covers the server side", "The proxy checks PROXY_KEY on /proxy/server only. GET /instances and client connections to /{id}/... are accepted without a key. Authentication of proxied clients happens on the server when the forwarded token is validated.", P + "Application.kt:80")
note("Logging and metrics differ between kRPC and REST", "Every HTTP request, REST included, is logged by the Ktor CallLogging plugin. The withLogging proxy with @LogParam is part of the kRPC registration only. RpcMetricsCollector.record is called from withMetrics, which is applied in RpcRegistry only, so the RPC metrics count kRPC calls.", S + "Application.kt:98")
note("Order of caching and authorization", "wrap() applies withCaching around the adapter that already carries withAuthorization, and the cache key is built from interface, method and arguments. No method carries @Cached, so nothing is cached at present.", S + "utils/Caching.kt:60")
note("IStorageService is REST only", "IStorageService is registered in RestRegistry and has no entry in RpcRegistry.", S + "routing/RestRegistry.kt:254")
note("No Ktor rate limit plugin", "The server installs no RateLimit plugin. The RateLimitState in core/HttpClient.kt paces outbound requests.", S + "core/HttpClient.kt:72")
note("The mirror page carries its own login", "GET /admin/mirror is defined outside the authenticate block and serves the HTML page. The API routes under it are inside authenticate.", S + "routing/Mirror.kt:88")

def build(root):
    return {
        "id": "api",
        "tab": "API surface",
        "noun": "element",
        "blurb": "How a request gets from a client to a table, and how the server pushes back. Arrows point from the caller to what it calls. Dotted lines are subscriptions on which the server streams.",
        "out": "Calls",
        "inn": "Called by",
        "layout": {"kind": "columns"},
        "groups": GROUPS,
        "styles": STYLES,
        "nodes": NODES,
        "edges": EDGES,
        "notes": NOTES,
    }
