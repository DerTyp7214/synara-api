S = "server/src/main/kotlin/dev/dertyp/"
SC = S + "services/credentials/"
UI = S + "services/ui/credentialserver/"
CS = "credential-server/src/main/kotlin/dev/dertyp/credentials/server/"
CC = "common-credentials/src/main/kotlin/dev/dertyp/credentials/"

GROUPS = [
    {"id": "admin", "label": "People", "column": 0, "desc": "Who drives the credential system from outside."},
    {"id": "consumers", "label": "Consuming services", "column": 1, "desc": "Server classes that resolve a credential before calling a third party or a tool."},
    {"id": "ui", "label": "Admin pages", "column": 1, "desc": "Server-driven UI contributions, all with requiresAdmin. They are reached through the generic IUiService RPC."},
    {"id": "kind-oauth", "label": "OAuth client credentials", "column": 2, "desc": "Kind OAUTH_CLIENT_CREDENTIALS. Resolved to an access token."},
    {"id": "kind-apple", "label": "Apple developer key", "column": 2, "desc": "Kind APPLE_DEVELOPER_KEY. Resolved to a signed developer token."},
    {"id": "kind-apikey", "label": "API keys", "column": 2, "desc": "Kinds API_KEY and API_KEY_PAIR. Handed out as stored."},
    {"id": "kind-tidal", "label": "Tidal device sessions", "column": 2, "desc": "Kind TIDAL_DEVICE_SESSION. Refreshed at Tidal on every hand-out and rendered as the importer's auth file."},
    {"id": "kind-file", "label": "Files", "column": 2, "desc": "Kind FILE. Base64 blobs by role."},
    {"id": "kind-plugin", "label": "Plugin names", "column": 2, "desc": "Any other name matching [a-z0-9._:-]{1,128}."},
    {"id": "targets", "label": "Where the credential ends up", "column": 2, "cols": 2, "desc": "Third-party hosts and command-line tools."},
    {"id": "server", "label": "Synara server credential layer", "column": 3, "desc": "Decides per name whether a credential comes from the local store or from the credential server."},
    {"id": "cs-routes", "label": "credential-server routes", "column": 4, "desc": "The HTTP surface of the credential server and its two auth checks."},
    {"id": "cs-brokers", "label": "credential-server brokers", "column": 5, "desc": "Turn a stored secret into what the consumer needs."},
    {"id": "cs-stores", "label": "credential-server storage", "column": 6, "desc": "Tables of the credential server database and the key that encrypts secrets."},
]

STYLES = [
    {"kind": "call", "dash": "", "label": "in-process call or lookup"},
    {"kind": "http", "dash": "dash", "label": "HTTP between processes"},
    {"kind": "file", "dash": "dot", "label": "file on disk, subprocess or direct database access"},
]

NAMES = [
    ("tidal.api", "kind-oauth", "Tidal API client credentials for metadata lookups. Token URL https://auth.tidal.com/v1/oauth2/token, Basic auth style.", 41),
    ("spotify.api", "kind-oauth", "Spotify Web API client credentials for metadata lookups. Form auth style.", 48),
    ("applemusic.developer", "kind-apple", "Apple Music developer key (.p8) used to sign developer tokens.", 55),
    ("youtube.api", "kind-apikey", "YouTube Data API key.", 60),
    ("acoustid.api", "kind-apikey", "AcoustID application API key.", 65),
    ("theaudiodb.api", "kind-apikey", "TheAudioDB API key. The local default is 123.", 70),
    ("linkresolver.api", "kind-apikey", "Link resolver API key.", 75),
    ("imagecache.token", "kind-apikey", "Image cache access token.", 80),
    ("podcastindex.api", "kind-apikey", "Podcast Index API key and secret, kind API_KEY_PAIR.", 85),
    ("importer.tiddl", "kind-tidal", "Tidal session for the tiddl importer, rendered as auth.json.", 90),
    ("importer.tdn", "kind-tidal", "Tidal session for the tidal-dl-ng importer, rendered as token.json.", 97),
    ("importer.gamdl", "kind-file", "Apple Music cookies and Widevine device file for the gamdl importer.", 104),
]

CONSUMERS = [
    ("c-tidal", "TidalService", "tidal.api", "t-tidal-api", "Authorization header",
     "Authorization header built from the token type and the app token. When a user is passed and SyncService has a Tidal token for that user, that token is sent instead.",
     S + "services/metadata/TidalService.kt:32", "http"),
    ("c-spotify", "SpotifyService", "spotify.api", "t-spotify-api", "Authorization header",
     "Authorization header built from the token type and the token.", S + "services/metadata/SpotifyService.kt:19", "http"),
    ("c-apple", "AppleMusicService", "applemusic.developer", "t-apple-catalog", "Bearer header",
     "Bearer developer token on catalog requests.", S + "services/metadata/AppleMusicService.kt:45", "http"),
    ("c-youtube", "YoutubeApiService", "youtube.api", "t-youtube-api", "&key= query",
     "The key as the key query parameter.", S + "services/youtube/YoutubeApiService.kt:97", "http"),
    ("c-acoustid", "AcoustIdService", "acoustid.api", "t-acoustid", "client parameter",
     "The key as the client parameter of the lookup.", S + "services/metadata/AcoustIdService.kt:178", "http"),
    ("c-audiodb", "TheAudioDBService", "theaudiodb.api", "t-audiodb", "key in URL path",
     "The key as a path segment of the API URL.", S + "services/metadata/TheAudioDBService.kt:26", "http"),
    ("c-linkresolver", "LinkResolverService", "linkresolver.api", "t-linkresolver", "X-API-Key header",
     "The key in the X-API-Key header.", S + "services/metadata/LinkResolverService.kt:30", "http"),
    ("c-imagecache", "ImageCacheService", "imagecache.token", "t-image-cache", "Bearer on PUT",
     "Bearer token on the PUT. An ApiKey or an AccessToken is accepted.", S + "services/metadata/ImageCacheService.kt:26", "http"),
    ("c-podcastindex", "PodcastIndexOrgIndex", "podcastindex.api", "t-podcast-index", "sha1 auth headers",
     "X-Auth-Key, X-Auth-Date and Authorization = sha1(key + secret + date).", S + "services/podcast/index/PodcastIndexOrgIndex.kt:59", "http"),
    ("c-tiddl", "TiddlService", "importer.tiddl", "t-tiddl", "auth.json",
     "When importer.tiddl is managed remotely, ~/.tiddl/auth.json is written with mode rw------- before each tiddl command. Otherwise the file already on disk is used.",
     S + "services/import/TiddlService.kt:43", "file"),
    ("c-tdn", "TdnService", "importer.tdn", "t-tdn", "token.json",
     "When importer.tdn is managed remotely, ~/.config/tidal_dl_ng/token.json is written with mode rw------- before each tdn command. Otherwise the file already on disk is used.",
     S + "services/import/TdnService.kt:118", "file"),
    ("c-gamdl", "GamdlService", "importer.gamdl", "t-gamdl", "cookies, .wvd",
     "The cookies file and the optional Widevine device file at the configured paths, passed as --cookies-path and --wvd-path. The materializer writes them only when importer.gamdl is managed remotely.",
     S + "services/gamdl/GamdlService.kt:41", "file"),
]

DELIVERED_AT = {
    "c-tidal": S + "services/metadata/TidalService.kt:133",
    "c-spotify": S + "services/metadata/SpotifyService.kt:29",
    "c-apple": S + "services/metadata/AppleMusicService.kt:98",
    "c-youtube": S + "services/youtube/YoutubeApiService.kt:148",
    "c-acoustid": S + "services/metadata/AcoustIdService.kt:191",
    "c-audiodb": S + "services/metadata/TheAudioDBService.kt:95",
    "c-linkresolver": S + "services/metadata/LinkResolverService.kt:130",
    "c-imagecache": S + "services/metadata/ImageCacheService.kt:61",
    "c-podcastindex": S + "services/podcast/index/PodcastIndexOrgIndex.kt:68",
    "c-tiddl": SC + "ImporterCredentialMaterializer.kt:42",
    "c-tdn": SC + "ImporterCredentialMaterializer.kt:42",
    "c-gamdl": S + "services/gamdl/GamdlService.kt:81",
}

TARGETS = [
    ("t-tidal-api", "Tidal API", "openapi.tidal.com"),
    ("t-spotify-api", "Spotify API", "api.spotify.com"),
    ("t-apple-catalog", "Apple Music catalog", "api.music.apple.com"),
    ("t-youtube-api", "YouTube Data API", "googleapis.com"),
    ("t-acoustid", "AcoustID", "api.acoustid.org"),
    ("t-audiodb", "TheAudioDB", "theaudiodb.com"),
    ("t-linkresolver", "LinkResolver", "linkresolver.synara.audio"),
    ("t-image-cache", "Image cache", "IMAGE_CACHE_URL"),
    ("t-podcast-index", "Podcast Index", "api.podcastindex.org"),
    ("t-tiddl", "tiddl", "CLI tool"),
    ("t-tdn", "tidal-dl-ng", "CLI tool"),
    ("t-gamdl", "gamdl", "CLI tool"),
    ("t-tidal-auth", "Tidal auth", "auth.tidal.com"),
    ("t-spotify-accounts", "Spotify accounts", "accounts.spotify.com"),
]

PAGES = [
    ("ui-entry", "credentials.entry", "Settings slot entry", "A list item in the settings slot that opens the credentials overview.",
     UI + "CredentialsEntryContribution.kt:14"),
    ("ui-overview", "credentials.overview", "Overview page", "Link status, client count and the list of local credentials with their origin.",
     UI + "CredentialsOverviewContribution.kt:22"),
    ("ui-local", "credentials.local", "Local credential page", "Save, clear and test one local credential. Save and clear are refused when the name is managed remotely.",
     UI + "LocalCredentialContribution.kt:138"),
    ("ui-server", "credentialserver.server", "Server page",
     "Save the URL, admin key, client id and secret, test, disconnect, register this server as a client, list credentials, create one from a preset or a custom name and kind.",
     UI + "CredentialServerServerContribution.kt:249"),
    ("ui-clients", "credentialserver.clients", "Clients page", "List and create clients. A new secret is kept in memory for 10 minutes per user so it can be shown.",
     UI + "CredentialServerClientsContribution.kt:84"),
    ("ui-client", "credentialserver.client", "Client page", "Enable or disable a client, toggle grants and write-back, rotate the secret, revoke tokens, delete.",
     UI + "CredentialServerClientContribution.kt:183"),
    ("ui-credential", "credentialserver.credential", "Credential page",
     "Save a credential of any kind including file uploads, test, delete, run a Tidal device login with a live stream, cancel it, or upload the server's local login.",
     UI + "CredentialServerCredentialContribution.kt:383"),
]


def node(id, label, group, desc, sub=None, meta=None, src=None, flags=None):
    item = {"id": id, "label": label, "group": group, "desc": desc, "meta": meta or []}
    if sub:
        item["sub"] = sub
    if src:
        item["src"] = src
    if flags:
        item["flags"] = flags
    return item


def flow(title, transport, carries, src, trigger=None, config=None):
    item = {"title": title, "transport": transport, "carries": carries, "src": src}
    if trigger:
        item["trigger"] = trigger
    if config:
        item["config"] = config
    return item


def edge(source, target, label, kind, *flows):
    return {"from": source, "to": target, "label": label, "kind": kind, "flows": list(flows)}


def name_id(name):
    return "n-" + name.replace(".", "-")


def nodes():
    result = [
        node("admin-app", "Admin in a client app", "admin",
             "A user with isAdmin using the server-driven settings pages in a Synara app.", "IUiService", [], S + "services/ui/UiService.kt:101"),
        node("operator", "Operator CLI", "admin",
             "The credential server jar started with arguments. It works on the database directly, without the HTTP routes.",
             "clients, credentials, keys", [], CS + "cli/CredentialCli.kt:249"),
    ]
    for id, label, sub, desc, src in PAGES:
        result.append(node(id, label, "ui", desc, sub, [["Access", "requiresAdmin"]], src))
    for id, label, name, target, delivery, text, src, kind in CONSUMERS:
        result.append(node(id, label, "consumers", "Resolves %s. %s" % (name, text), name, [["Delivery", delivery]], src))
    result.append(node("c-plugin", "Plugin code", "consumers",
                       "A plugin calling context.credentials. No code in the server or plugin-api sources of this repository calls it.", "context.credentials",
                       [["Interface", "PluginCredentials: managedRemotely, get, store, remove"]],
                       "plugin-api/src/main/kotlin/dev/dertyp/plugins/PluginCredentials.kt:5"))
    for name, group, desc, line in NAMES:
        result.append(node(name_id(name), name, group, desc, None, [["Preset", "yes"]], CS + "broker/CredentialPresets.kt:%d" % line))
    result.append(node("n-plugin", "plugin:<id>:<name>", "kind-plugin",
                       "Names owned by a plugin. Locally they are encrypted rows credential.<name> in the plugin's settings.", None,
                       [["Preset", "no"]], CC + "CredentialProtocol.kt:45"))
    result += [
        node("routing", "RoutingCredentialProvider", "server",
             "The bound CredentialProvider. A name managed remotely is resolved remotely. Any other name is tried locally first, and after a local miss the grants are refreshed once and the remote side is tried.",
             "CredentialProvider", [["Remote miss", "returns null and logs a warning, the local store is not consulted"]],
             SC + "RoutingCredentialProvider.kt:23"),
        node("local-provider", "LocalCredentialProvider", "server",
             "Resolves from the local store. It does its own client credentials exchange for Tidal and Spotify and signs Apple developer tokens itself.",
             "local mode", [["Apple token TTL", "30 min"]], SC + "LocalCredentialProvider.kt:54"),
        node("local-store", "LocalCredentialStore", "server",
             "Settings rows encrypted with AES-GCM, with the configuration values from the environment as the fallback. Most names live under plugin id credentials. acoustid.api and podcastindex.api keep their own credential sources.",
             "stored, then env", [["Key", "CREDENTIALS_ENCRYPTION_KEY, else the key file CREDENTIALS_KEY_FILE, default ~/.config/synara/credentials.key, generated when missing"]], SC + "LocalCredentialStore.kt:75"),
        node("cc-exchange", "ClientCredentialsExchange", "server",
             "OAuth client credentials exchange done by the server itself in local mode. Tokens are cached in memory until 60 s before expiry.",
             "local OAuth", [], SC + "ClientCredentialsExchange.kt:72"),
        node("apple-signer", "AppleDeveloperTokenSigner", "server",
             "Signs the Apple developer token locally from team id, key id and the .p8 key.", "local signing", [],
             SC + "AppleDeveloperTokenSigner.kt:25"),
        node("local-plugin-store", "LocalPluginCredentialStore", "server",
             "Encrypted PluginSettingTable rows named credential.<name>, one set per plugin.", "plugin settings", [],
             SC + "LocalPluginCredentialStore.kt:43"),
        node("plugin-factory", "PluginCredentialsFactory", "server",
             "Hands each plugin a PluginCredentials scoped to its id. get asks RemoteCredentialProvider when the name is granted and LocalPluginCredentialStore otherwise. store and remove are refused for names managed remotely.",
             "per plugin id", [], SC + "PluginCredentialsFactory.kt:12"),
        node("materializer", "Credential materializer", "server",
             "Wraps the commands of importers that declare a credential name. When the name is managed remotely it takes a per-name mutex, fetches the files, writes them, runs the command, compares the files afterwards and writes changes back. Otherwise it only runs the command.",
             "ImporterCredentialMaterializer", [["Lock", "one Mutex per name, in process memory"]], SC + "ImporterCredentialMaterializer.kt:21"),
        node("remote-provider", "RemoteCredentialProvider", "server",
             "Knows which names are granted from the last token exchange. Caches API keys and pairs for 5 minutes and tokens until 60 s before expiry. Files are never cached.",
             "grants and cache", [["Mode", "REMOTE when url, clientId and clientSecret are all set"]], SC + "remote/RemoteCredentialProvider.kt:125"),
        node("connection", "CredentialServerConnection", "server",
             "URL, client id, client secret and admin key. Values stored in settings under plugin id credentialserver are used when a URL is stored, otherwise the environment values.",
             "stored, then env", [["Env", "CREDENTIAL_SERVER_URL, CREDENTIAL_SERVER_CLIENT_ID, CREDENTIAL_SERVER_CLIENT_SECRET, CREDENTIAL_SERVER_ADMIN_KEY"]],
             SC + "CredentialServerConnection.kt:39"),
        node("cs-client", "CredentialServerClient", "server",
             "HTTP client for the consumer routes. Caches the token, exchanges it again 60 s before expiry, retries once on 401 and refreshes the grants on 403.",
             "consumer HTTP client", [], SC + "remote/CredentialServerClient.kt:81"),
        node("admin-client", "CredentialServerAdminClient", "server",
             "HTTP client for the admin routes, sending X-Admin-Key.", "admin HTTP client", [], SC + "admin/CredentialServerAdminClient.kt:152"),

        node("consumer-routes", "Consumer routes", "cs-routes",
             "GET /health, POST /token, GET /credentials, GET /credentials/{name}, PUT /credentials/{name}/files.",
             "/token, /credentials", [["Errors", "401, 403 NOT_GRANTED, 404, 409 CONFLICT, 424 NEEDS_LOGIN, 502 UPSTREAM_FAILED"]],
             CS + "routes/ConsumerRoutes.kt:22"),
        node("admin-routes", "Admin routes", "cs-routes",
             "/admin/clients with rotate-secret, revoke-tokens and grants, /admin/presets, /admin/credentials with test and tidal-login, /admin/tidal-logins, /admin/signing-keys/rotate.",
             "/admin/*", [], CS + "routes/AdminRoutes.kt:30"),
        node("consumer-auth", "Consumer auth", "cs-routes",
             "Verifies the ES256 JWT and rechecks on every request that the client is enabled and its tokenVersion equals the ver claim.",
             "ES256 JWT, 900 s", [["Claims", "sub, ver, grt, gwb, jti"], ["Issuer", "CREDENTIAL_SERVER_ISSUER, default synara-credentials"], ["Lifetime", "CREDENTIAL_SERVER_TOKEN_TTL_SECONDS, default 900"]], CS + "auth/ConsumerAuth.kt:35"),
        node("admin-auth", "Admin auth", "cs-routes",
             "One shared admin key compared in constant time. Answers 503 while the key is unset and 401 on a mismatch.",
             "X-Admin-Key", [], CS + "auth/AdminAuth.kt:26"),

        node("resolver", "CredentialResolver", "cs-brokers",
             "Loads the stored secret under a per-name mutex, dispatches to the broker for its kind and saves the updated secret and status.",
             "per-name mutex", [], CS + "broker/CredentialResolver.kt:28"),
        node("oauth-broker", "OAuthClientCredentialsBroker", "cs-brokers",
             "Posts the stored client id and secret to the stored token URL and caches the token in memory until 60 s before expiry.",
             "client_credentials", [], CS + "broker/OAuthClientCredentialsBroker.kt:22"),
        node("tidal-broker", "TidalSessionBroker", "cs-brokers",
             "Refreshes the session at Tidal on every hand-out and stores the rotated refresh token. The fingerprint is the SHA-256 of the refresh token.",
             "refresh on hand-out", [], CS + "broker/TidalSessionBroker.kt:10"),
        node("apple-broker", "AppleDeveloperTokenBroker", "cs-brokers",
             "Signs the developer token locally without a network call and caches it until 5 minutes before expiry.",
             "local signing", [["Default TTL", "43200 s"]], CS + "broker/AppleDeveloperTokenBroker.kt:25"),
        node("apikey-broker", "ApiKeyBroker", "cs-brokers",
             "Returns a stored API key or key pair as it is.", "API_KEY, API_KEY_PAIR", [], CS + "broker/ApiKeyBroker.kt:6"),
        node("file-broker", "FileBroker", "cs-brokers",
             "Stores base64 blobs by role and merges a write-back by role. The fingerprint is the SHA-256 of all file bytes. The expiry of the gamdl cookie media-user-token drives the status.",
             "FILE", [], CS + "broker/FileBroker.kt:10"),
        node("device-login", "TidalDeviceLoginManager", "cs-brokers",
             "Starts a Tidal device authorization, polls for the token in a background coroutine and saves the session when it is granted. Login state is held in memory and kept 10 minutes after the end.",
             "device login", [], CS + "broker/TidalDeviceLoginManager.kt:43"),

        node("cs-client-table", "cs_client", "cs-stores",
             "clientId, name, secretHash, tokenVersion, enabled, createdAt, lastTokenAt. The secret is stored as a SHA-256 hash.",
             "clients", [], CS + "db/Tables.kt:9"),
        node("cs-credential-table", "cs_credential", "cs-stores",
             "name, kind, description, secret, fingerprint, status, statusMessage, expiresAt. Only the latest value is kept.",
             "credentials", [["Status", "OK, EXPIRING, EXPIRED, NEEDS_LOGIN, ERROR"]], CS + "db/Tables.kt:19"),
        node("cs-grant-table", "cs_grant", "cs-stores",
             "client, credential, writeBack, lastUsedAt. Deleted with its client or credential.", "grants", [], CS + "db/Tables.kt:32"),
        node("cs-signing-key-table", "cs_signing_key", "cs-stores",
             "kid, privateKey, publicKey, active. Old public keys stay for verification after a rotation.", "signing keys", [],
             CS + "db/Tables.kt:44"),
        node("master-key", "SecretBox master key", "cs-stores",
             "AES-256-GCM with the prefix enc:v1:. The key comes from CREDENTIAL_SERVER_MASTER_KEY, else a key file, else a generated file with mode 0600.",
             "AES-256-GCM", [], CS + "crypto/SecretBox.kt:58"),
    ]
    for id, label, sub in TARGETS:
        result.append(node(id, label, "targets", "Receives the credential or issues the token.", sub, []))
    return result


def edges():
    result = []
    for id, label, sub, desc, src in PAGES:
        result.append(edge("admin-app", id, "IUiService", "call",
                           flow("Open and use %s" % sub, "kRPC IUiService render, renderSlot and invoke", desc, src, "admin user action", "user.isAdmin")))
    result += [
        edge("admin-app", "c-gamdl", "setImportCredentials", "call",
             flow("Upload gamdl files", "kRPC IImportService.setImportCredentials", "cookies and Widevine device file stored locally. Rejected when importer.gamdl is managed remotely.",
                  S + "services/gamdl/GamdlService.kt:107", "admin action", "@RequiresAdmin")),
        edge("operator", "cs-client-table", "CLI", "file",
             flow("Manage clients", "jar arguments, direct database access", "clients, grants and signing keys", CS + "cli/CredentialCli.kt:46", "manual")),
        edge("operator", "cs-credential-table", "CLI", "file",
             flow("Manage credentials", "jar arguments, direct database access", "credentials and Tidal logins", CS + "cli/CredentialCli.kt:76", "manual")),

        edge("ui-overview", "local-store", "reads", "call",
             flow("Local credential list", "LocalCredentialStore", "each local credential with its state: stored, environment, unreadable, none, default or remote",
                  UI + "CredentialsOverviewContribution.kt:47", "page render")),
        edge("ui-overview", "admin-client", "reads", "call",
             flow("Link status and client count", "CredentialServerAdminClient", "health and, when the admin key is accepted, the number of clients. The admin probe is GET /admin/clients, cached 30 s.",
                  UI + "CredentialServerUi.kt:129", "page render")),
        edge("ui-local", "local-store", "save, clear", "call",
             flow("Edit a local credential", "LocalCredentialStore", "save and clear one credential. The test action resolves through CredentialProvider instead.", UI + "LocalCredentialContribution.kt:138", "admin action")),
        edge("ui-server", "connection", "stores", "call",
             flow("Save or clear the connection", "CredentialServerConnectionSource.store and clear", "URL, admin key, client id and client secret, encrypted in settings",
                  UI + "CredentialServerServerContribution.kt:249", "admin action"),
             flow("Register this server as a client", "ui.connection.store after createClient", "only clientId and clientSecret of the new client are stored",
                  UI + "CredentialServerServerContribution.kt:331", "admin action")),
        edge("ui-server", "admin-client", "admin calls", "call",
             flow("Register, list, create", "CredentialServerAdminClient", "createClient with a grant for every existing core name and write-back only for Tidal sessions, the credential list, creation from a preset or a custom name",
                  UI + "CredentialServerServerContribution.kt:319", "admin action")),
        edge("ui-clients", "admin-client", "admin calls", "call",
             flow("List and create clients", "CredentialServerAdminClient", "client list and new clients", UI + "CredentialServerClientsContribution.kt:92", "admin action")),
        edge("ui-client", "admin-client", "admin calls", "call",
             flow("Change one client", "CredentialServerAdminClient", "enable, grants, write-back, rotate secret, revoke tokens, delete",
                  UI + "CredentialServerClientContribution.kt:183", "admin action")),
        edge("ui-credential", "admin-client", "admin calls", "call",
             flow("Save, test, delete", "CredentialServerAdminClient", "a credential of any kind including file uploads", UI + "CredentialServerCredentialContribution.kt:383",
                  "admin action"),
             flow("Tidal device login", "start, live event stream, cancel", "device code, verification URL and state events shown live in the page",
                  UI + "CredentialServerCredentialContribution.kt:449", "admin action"),
             flow("Use local login", "upload of the server's own auth.json or token.json", "the local Tidal session. TIDDL_AUTH on the server fills blank client fields for tiddl sessions.",
                  UI + "CredentialServerCredentialContribution.kt:475", "admin action")),
    ]
    for id, label, name, target, delivery, text, src, kind in CONSUMERS:
        resolver = "CredentialProvider.resolve" if kind == "http" else "CredentialProvider.resolve, called by ImporterCredentialMaterializer"
        moment = "before the call" if kind == "http" else "before the tool run, when the name is managed remotely"
        result.append(edge(id, name_id(name), "resolves", "call",
                           flow("Resolve %s" % name, resolver, name, src, moment)))
        result.append(edge(id, target, delivery, kind,
                           flow("Deliver %s" % name, delivery, text, DELIVERED_AT[id])))
    for name, group, desc, line in NAMES:
        result.append(edge(name_id(name), "routing", "resolve(name)", "call",
                           flow("Route %s" % name, "RoutingCredentialProvider.resolve", "remote when the name is in the grants, otherwise local first",
                                SC + "RoutingCredentialProvider.kt:23")))
    result += [
        edge("c-plugin", "plugin-factory", "context.credentials", "call",
             flow("Plugin credential access", "PluginCredentials get, store, remove", "a name scoped to the plugin id", SC + "PluginCredentialsFactory.kt:20", "plugin code")),
        edge("plugin-factory", "n-plugin", "resolves", "call",
             flow("Resolve a plugin name", "the full name plugin:<id>:<name>", "RemoteCredentialProvider when granted, else the local row", SC + "PluginCredentialsFactory.kt:21")),
        edge("plugin-factory", "local-plugin-store", "store, remove", "call",
             flow("Read, store or remove locally", "LocalPluginCredentialStore", "an encrypted row credential.<name>. get reads it when the name is not granted. store and remove are refused when the name is managed remotely.",
                  SC + "PluginCredentialsFactory.kt:27")),
        edge("c-tiddl", "materializer", "withFiles", "call",
             flow("Wrap tiddl commands", "ImporterCredentialMaterializer.withFiles", "every tiddl command run through executeImporter. login runs no command when the name is managed remotely.",
                  S + "services/import/BaseImporter.kt:338")),
        edge("c-tdn", "materializer", "withFiles", "call",
             flow("Wrap tdn commands", "ImporterCredentialMaterializer.withFiles", "every tdn command run through executeImporter", S + "services/import/BaseImporter.kt:338")),
        edge("c-gamdl", "materializer", "withFiles", "call",
             flow("Wrap gamdl commands", "ImporterCredentialMaterializer.withFiles", "every gamdl command run through executeImporter", S + "services/import/BaseImporter.kt:338")),
        edge("materializer", "routing", "fetch, writeBack", "call",
             flow("Fetch files", "CredentialProvider.resolve returning Files", "auth.json, token.json or cookies and device file", SC + "ImporterCredentialMaterializer.kt:24",
                  "before the command, when the name is managed remotely"),
             flow("Write back changed files", "CredentialProvider.writeBack with the expected fingerprint", "files the tool changed during the run",
                  SC + "ImporterCredentialMaterializer.kt:61", "after the command, only when a file changed")),

        edge("routing", "local-provider", "not granted", "call",
             flow("Local first", "LocalCredentialProvider.resolve", "names that are not in the grants of the last token exchange", SC + "RoutingCredentialProvider.kt:25")),
        edge("routing", "remote-provider", "granted", "call",
             flow("Remote for granted names", "RemoteCredentialProvider.resolve and writeBack", "names in the grants. A failed fetch returns null with a warning.",
                  SC + "RoutingCredentialProvider.kt:28"),
             flow("Refresh after a local miss", "RemoteCredentialProvider.refreshAfterLocalMiss", "one token exchange at most every 60 s, then a remote try when the name is now granted",
                  SC + "RoutingCredentialProvider.kt:26")),
        edge("local-provider", "local-store", "reads", "call",
             flow("Stored or environment value", "LocalCredentialStore.current", "the decrypted setting, else the configuration value from the environment", SC + "LocalCredentialStore.kt:207")),
        edge("local-provider", "cc-exchange", "OAuth", "call",
             flow("Local token exchange", "ClientCredentialsExchange", "tidal.api and spotify.api in local mode", SC + "LocalCredentialProvider.kt:100")),
        edge("local-provider", "apple-signer", "signs", "call",
             flow("Local developer token", "AppleDeveloperTokenSigner", "applemusic.developer in local mode, 30 min TTL", SC + "LocalCredentialProvider.kt:57")),
        edge("local-provider", "local-plugin-store", "reads", "call",
             flow("Plugin names", "LocalPluginCredentialStore.get", "plugin:<id>:<name> rows", SC + "LocalCredentialProvider.kt:73")),
        edge("cc-exchange", "t-tidal-auth", "POST token", "http",
             flow("Client credentials at Tidal", "HTTPS POST https://auth.tidal.com/v1/oauth2/token with Basic auth", "the app access token",
                  SC + "ClientCredentialsExchange.kt:80", "first use and 60 s before expiry", "Tidal client id and secret")),
        edge("cc-exchange", "t-spotify-accounts", "POST token", "http",
             flow("Client credentials at Spotify", "HTTPS POST https://accounts.spotify.com/api/token, form style", "the app access token",
                  SC + "ClientCredentialsExchange.kt:80", "first use and 60 s before expiry", "Spotify client id and secret")),
        edge("remote-provider", "cs-client", "fetch", "call",
             flow("Token, grants and credentials", "CredentialServerClient", "token exchange, credential fetch and file write-back", SC + "remote/RemoteCredentialProvider.kt:138")),
        edge("remote-provider", "connection", "observes", "call",
             flow("Connection changes", "collects the settings change flow", "a new connection triggers a token exchange and clears the cache",
                  SC + "remote/RemoteCredentialProvider.kt:51", "connection saved or cleared")),
        edge("cs-client", "consumer-routes", "HTTP", "http",
             flow("Token exchange", "HTTP POST /token", "clientId and clientSecret, returns the JWT, expiresAt and the grant list", SC + "remote/CredentialServerClient.kt:91",
                  "connect, 60 s before expiry, one retry on 401, a 403, a local miss, an admin mutation"),
             flow("Credential fetch", "HTTP GET /credentials/{name} with Bearer", "access token, developer token, key, pair or files",
                  SC + "remote/CredentialServerClient.kt:108", "on demand, cached except for files"),
             flow("File write-back", "HTTP PUT /credentials/{name}/files with Bearer", "changed files and the expected fingerprint", SC + "remote/CredentialServerClient.kt:119",
                  "after an importer command changed a file", "write-back grant")),
        edge("admin-client", "consumer-routes", "GET /health", "http",
             flow("Health", "HTTP GET /health", "ok, protocolVersion and jar version", SC + "admin/CredentialServerAdminClient.kt:58", "page render with a 5 s timeout, and the test action")),
        edge("admin-client", "admin-routes", "HTTP", "http",
             flow("Clients", "HTTP /admin/clients* with X-Admin-Key", "list, create, patch, delete, rotate-secret, revoke-tokens, grants",
                  SC + "admin/CredentialServerAdminClient.kt:61", "admin UI"),
             flow("Credentials and presets", "HTTP /admin/credentials*, /admin/presets", "list, upsert, delete, test", SC + "admin/CredentialServerAdminClient.kt:81", "admin UI"),
             flow("Tidal logins", "POST /admin/credentials/{name}/tidal-login, GET /admin/tidal-logins/{id}/events as NDJSON, DELETE", "login start, state events until the login leaves PENDING, cancel",
                  SC + "admin/CredentialServerAdminClient.kt:96", "admin UI")),

        edge("consumer-routes", "consumer-auth", "Bearer", "call",
             flow("Authenticate the consumer", "JWT verification", "every route except /health and /token", CS + "routes/ConsumerRoutes.kt:47")),
        edge("consumer-auth", "cs-client-table", "checks", "call",
             flow("Enabled and token version", "lookup per request", "the client must be enabled and tokenVersion must equal the ver claim, so a revocation applies at once",
                  CS + "auth/ConsumerAuth.kt:40")),
        edge("consumer-auth", "cs-signing-key-table", "verifies", "call",
             flow("Signing keys", "TokenIssuer", "the active ES256 key signs, a token is verified with the stored public key of its kid", CS + "crypto/TokenIssuer.kt:58")),
        edge("consumer-routes", "cs-client-table", "POST /token", "call",
             flow("Check the client secret", "SHA-256 hash comparison", "401 on an unknown, wrong or disabled client. Stamps lastTokenAt.", CS + "routes/ConsumerRoutes.kt:35")),
        edge("consumer-routes", "cs-grant-table", "grants", "call",
             flow("Grant check and listing", "GET /credentials and GET /credentials/{name}", "GET /credentials lists the grants of the client from the table. GET /credentials/{name} answers 403 NOT_GRANTED unless the name is in the JWT grants, then touches lastUsedAt.",
                  CS + "routes/ConsumerRoutes.kt:55")),
        edge("consumer-routes", "resolver", "resolve", "call",
             flow("Resolve", "CredentialResolver.resolve", "the credential for GET /credentials/{name}", CS + "routes/ConsumerRoutes.kt:61"),
             flow("Write-back", "CredentialResolver.writeBack", "files from PUT /credentials/{name}/files, accepted for Tidal sessions and files when the name is in the gwb claim", CS + "routes/ConsumerRoutes.kt:76")),
        edge("admin-routes", "admin-auth", "X-Admin-Key", "call",
             flow("Authenticate the admin", "header comparison", "every /admin route", CS + "routes/AdminRoutes.kt:31")),
        edge("admin-routes", "cs-client-table", "CRUD", "call",
             flow("Client administration", "CredentialStore", "create, patch, delete, rotate secret, revoke. tokenVersion is bumped on enable or disable, rotation, revoke, a grant change and the deletion of a granted credential.",
                  CS + "routes/AdminRoutes.kt:44")),
        edge("admin-routes", "cs-grant-table", "grants", "call",
             flow("Set grants", "PUT /admin/clients/{id}/grants", "credential names with the writeBack flag", CS + "routes/AdminRoutes.kt:77")),
        edge("admin-routes", "cs-credential-table", "CRUD", "call",
             flow("Credential administration", "GET, PUT, DELETE /admin/credentials/{name}", "the stored secret per kind", CS + "routes/AdminRoutes.kt:90")),
        edge("admin-routes", "cs-signing-key-table", "rotate", "call",
             flow("Rotate signing keys", "POST /admin/signing-keys/rotate", "a new active key, old public keys kept", CS + "routes/AdminRoutes.kt:36")),
        edge("admin-routes", "resolver", "test", "call",
             flow("Test a credential", "POST /admin/credentials/{name}/test", "a resolve run whose result becomes the status", CS + "routes/AdminRoutes.kt:113")),
        edge("admin-routes", "device-login", "tidal-login", "call",
             flow("Start, watch, cancel", "POST /admin/credentials/{name}/tidal-login, GET /admin/tidal-logins/{id} and /events, DELETE", "the login snapshot and its event stream",
                  CS + "routes/AdminRoutes.kt:119")),
        edge("resolver", "cs-credential-table", "load, save", "call",
             flow("Load and save the secret", "CredentialStore", "the stored secret, the updated secret after a refresh, and the status", CS + "broker/CredentialResolver.kt:28")),
        edge("resolver", "master-key", "decrypts", "call",
             flow("Encryption at rest", "SecretBox with AAD credential:<name>", "secrets are decrypted on load and encrypted on save", CS + "db/CredentialStore.kt:243")),
        edge("resolver", "oauth-broker", "OAUTH", "call",
             flow("OAuth client credentials", "dispatch by secret type", "tidal.api, spotify.api and custom OAuth credentials", CS + "broker/CredentialResolver.kt:155")),
        edge("resolver", "tidal-broker", "TIDAL session", "call",
             flow("Tidal session", "dispatch by secret type, and writeBack", "importer.tiddl and importer.tdn", CS + "broker/CredentialResolver.kt:155")),
        edge("resolver", "apple-broker", "APPLE key", "call",
             flow("Apple developer key", "dispatch by secret type", "applemusic.developer", CS + "broker/CredentialResolver.kt:155")),
        edge("resolver", "apikey-broker", "API key", "call",
             flow("API key and pair", "dispatch by secret type", "the five API keys and podcastindex.api", CS + "broker/CredentialResolver.kt:155")),
        edge("resolver", "file-broker", "FILE", "call",
             flow("Files", "dispatch by secret type, and writeBack", "importer.gamdl", CS + "broker/CredentialResolver.kt:155")),
        edge("oauth-broker", "t-tidal-auth", "POST token", "http",
             flow("Client credentials for tidal.api", "HTTPS POST to the stored token URL with Basic auth", "an app access token", CS + "broker/OAuthClientCredentialsBroker.kt:47",
                  "a fetch when the cached token expired")),
        edge("oauth-broker", "t-spotify-accounts", "POST token", "http",
             flow("Client credentials for spotify.api", "HTTPS POST to the stored token URL, form style", "an app access token", CS + "broker/OAuthClientCredentialsBroker.kt:47",
                  "a fetch when the cached token expired")),
        edge("tidal-broker", "t-tidal-auth", "refresh_token", "http",
             flow("Refresh on every hand-out", "HTTPS POST token with grant refresh_token", "a fresh access token and, when Tidal rotates it, a new refresh token that is stored",
                  CS + "broker/TidalSessionBroker.kt:16", "every fetch or test of a Tidal session")),
        edge("device-login", "t-tidal-auth", "device flow", "http",
             flow("Device authorization and polling", "HTTPS POST device_authorization with scope r_usr+w_usr+w_sub, then token polling", "loginId, verificationUri, userCode, then the session",
                  CS + "broker/TidalDeviceLoginManager.kt:53", "admin starts a login, polling at Tidal's interval")),
        edge("device-login", "cs-credential-table", "saves", "call",
             flow("Save the granted session", "CredentialStore", "the encrypted session, after which the login event becomes COMPLETED", CS + "broker/TidalDeviceLoginManager.kt:135")),
    ]
    return result


def notes():
    return [
        {"title": "One client per Synara server",
         "text": "The register action creates a client for the Synara server it runs on. Grants travel in the JWT claims grt and gwb, so a server sees a changed grant after its next token exchange.",
         "src": CS + "crypto/TokenIssuer.kt:47"},
        {"title": "A Tidal session is refreshed on every hand-out",
         "text": "TidalSessionBroker.resolve calls the refresh grant each time and stores the result, so servers fetching one after another each receive a fresh token.",
         "src": CS + "broker/TidalSessionBroker.kt:16"},
        {"title": "Concurrent use is guarded by a mutex and a fingerprint",
         "text": "CredentialResolver holds a per-name mutex inside the credential server process. A write-back whose expectedFingerprint no longer matches is answered with CONFLICT. The materializer on the Synara side holds one Mutex per name in process memory.",
         "src": CS + "broker/TidalSessionBroker.kt:35"},
        {"title": "Registration stores the client id and secret only",
         "text": "The register action stores clientId and clientSecret. CredentialSource.current uses the stored values only when build finds a stored URL, and falls back to the environment values otherwise.",
         "src": SC + "CredentialServerConnection.kt:39"},
        {"title": "A granted name does not fall back to the local store",
         "text": "For a name managed remotely RoutingCredentialProvider returns what the remote side returns. When that is null it logs a warning and returns null.",
         "src": SC + "RoutingCredentialProvider.kt:28"},
        {"title": "Default Tidal client for the importer sessions",
         "text": "For importer.tiddl and importer.tdn the client is the typed value, else the stored one, else the constants TIDAL_IMPORTER_CLIENT_ID and TIDAL_IMPORTER_CLIENT_SECRET in CredentialPresets.",
         "src": CS + "broker/CredentialPresets.kt:15"},
        {"title": "The yt-dlp importers use no credential",
         "text": "BaseImporter.executeImporter wraps a command in the materializer only when the importer declares a credentialName. TiddlService, TdnService and GamdlService declare one. BaseYtdlpImporter declares none. youtube.api is resolved by YoutubeApiService.",
         "src": S + "services/import/BaseImporter.kt:337"},
    ]


def build(root):
    return {
        "id": "credentials",
        "tab": "Credentials",
        "noun": "part",
        "blurb": "How a credential gets from where it is stored to the service or tool that needs it. Read left to right: a consumer resolves a name, the router picks the local store or the credential server, and a broker turns the stored secret into a token, a key or a file.",
        "out": "Uses",
        "inn": "Used by",
        "layout": {"kind": "columns"},
        "groups": GROUPS,
        "styles": STYLES,
        "nodes": nodes(),
        "edges": edges(),
        "notes": notes(),
    }
