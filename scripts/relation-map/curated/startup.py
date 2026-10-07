S = "server/src/main/kotlin/dev/dertyp/"
APP = S + "Application.kt"

GROUPS = [
    {"id": "boot", "label": "Process setup", "column": 0, "desc": "Logging, discovery, dependency injection and hooks."},
    {"id": "database", "label": "Database", "column": 1, "desc": "Connect, check the schema base, migrate."},
    {"id": "setup", "label": "First-run setup", "column": 2, "desc": "Only when a setup source is configured and the database is empty."},
    {"id": "exit", "label": "Process exits", "column": 2, "desc": "Runtime.halt, because exitProcess inside the module waits for Ktor's shutdown hook."},
    {"id": "scheduling", "label": "Scheduling", "column": 3},
    {"id": "background", "label": "Launched in the background", "column": 3, "desc": "Started on ApplicationScope and not awaited by the steps that follow."},
    {"id": "serving", "label": "HTTP and routing", "column": 4},
    {"id": "services", "label": "Service starts", "column": 5, "desc": "Each start is its own launch on ApplicationScope."},
    {"id": "shutdown", "label": "Shutdown", "column": 6, "desc": "Runs on ApplicationStopping."},
]

STYLES = [
    {"kind": "then", "dash": "", "label": "next step"},
    {"kind": "branch", "dash": "dash", "label": "only under a condition"},
    {"kind": "async", "dash": "dot", "label": "launched, not awaited"},
]

STEPS = [
    ("logging", "Logging and banner", "SLF4J bridge, tag options", "boot", "Installs the SLF4J bridge, sets the jaudiotagger options and prints the banner from BuildConfig.", APP + ":78"),
    ("call-logging", "install(CallLogging)", "request log", "boot", "Ktor's CallLogging plugin. Every HTTP request is logged here, the REST routes included.", APP + ":98"),
    ("mdns", "install(JmDNSPlugin)", "mDNS advertisement", "boot", "Registers the service synara-api of type _synara-api._tcp.local. when the application has started. A failure is not fatal.", APP + ":99"),
    ("koin", "install(Koin)", "mainModule, 20 modules", "boot", "One root module that includes core, config, system, auth, library, listening, audio, credentials, metadata, release, import, intake, podcast, cover, ui, schedule, sync, hue, subsonic and mcp.", APP + ":105"),
    ("hooks", "configureHooks", "subscribers register", "boot", "Every HookSubscriber bound in Koin subscribes to the HookService. This runs before the database is connected, so subscribers only register here.", S + "Hooks.kt:9"),
    ("early-services", "HTTP clients start", "and DatabaseManager registers", "boot", "Registers DatabaseManager with ServiceLifecycle and starts HttpClientFactory and HttpClientQueueService.", APP + ":111"),
    ("database-init", "DatabaseManager.init", "pool and connection", "database", "Builds the Hikari pool (SQLite: one connection, WAL, foreign keys on. Otherwise 100 connections, PostgreSQL with jit off), configures Flyway for the dialect and connects Exposed.", S + "services/DatabaseManager.kt:49"),
    ("base-check", "MigrationBaseCheck", "is the database at the base", "database", "Read-only check before any migration. Refuses a database with tables but no history, a failed migration, a version below the base 1.109, a migration this build lacks, or unfinished custom migrations of 0.0.1.", S + "services/DatabaseManager.kt:91"),
    ("flyway", "flyway.migrate", "SQL migrations", "database", "Runs the migrations under db/migrations/postgres or db/migrations/sqlite. An empty database gets the base B1_109.", S + "services/DatabaseManager.kt:92"),
    ("seed-admin", "Seed the first admin", "insertIgnore", "database", "Inserts the admin user from the configured admin client id and secret when it does not exist.", S + "services/DatabaseManager.kt:30"),
    ("cache", "configureCache", "Redis or memory", "database", "Installs SimpleCache with Redis when it is enabled, otherwise a memory cache of 10 seconds. Registers the RedisCacheProvider for shutdown and calls RedisSearchService.initIndex.", S + "HTTP.kt:31"),
    ("setup-check", "Setup source configured?", "and database empty", "setup", "Looks at SETUP_FROM_BACKUP and SETUP_FROM_MIRROR_URL. The setup runs only when the database has no songs and at most one user.", APP + ":129"),
    ("setup-backup", "Load the backup", "BackupService.loadBackup", "setup", "Restores the database and the images from the given backup file.", APP + ":140"),
    ("setup-mirror", "Mirror another server", "startMirror", "setup", "Pulls the library and the users from another Synara server over kRPC, with the given admin username and password, and waits until the mirror has finished.", APP + ":162"),
    ("ensure-defaults", "ensureDefaults", "task configurations", "scheduling", "Inserts the default configuration of every worker whose key has no row yet. This call blocks.", APP + ":200"),
    ("scheduled-tasks", "configureScheduledTasks", "register the workers", "scheduling", "Registers every Worker from Koin that carries @WorkerTask as a managed task under its key.", S + "core/ScheduledTasks.kt:13"),
    ("schedule-start", "ScheduleService starts", "the scheduler loop", "scheduling", "ServiceLifecycle.start launches ScheduleService.startService, which collects the stored configurations and runs the scheduler loop. The module does not wait for it.", APP + ":205"),
    ("http", "configureHTTP", "CORS and JWT", "serving", "Installs CORS and the JWT authentication provider.", S + "HTTP.kt:20"),
    ("routing", "configureRouting", "all routes", "serving", "Installs ContentNegotiation, SSE, WebSockets, KHealth, OpenApi, Compression and StatusPages, then mounts the static resources, the OpenAPI document, the kRPC endpoints, the generated REST routes, the handshake socket, the hand-written auth routes, radio, MCP and the mirror admin routes.", S + "Routing.kt:51"),
    ("ui-contributions", "Core UI and intake", "contributions register", "serving", "CoreUiContributions registers the server's own UI contributions and ImporterResolvers registers its resolver provider with IntakeService.", APP + ":221"),
    ("configure-services", "configureServices", "start the services", "services", "Starts the remaining services in a fixed order. Each start is launched on ApplicationScope, so the calls return at once.", S + "Services.kt:21"),
    ("configure-shutdown", "configureShutdown", "subscribe to stopping", "serving", "Subscribes the shutdown sequence to ApplicationStopping. This is the last step of the module.", APP + ":224"),
]

SEQUENCE = [
    "logging", "call-logging", "mdns", "koin", "hooks", "early-services", "database-init", "base-check", "flyway", "seed-admin", "cache",
    "setup-check", "ensure-defaults", "scheduled-tasks", "schedule-start", "http", "routing", "ui-contributions", "configure-services", "configure-shutdown",
]

SERVICE_STARTS = [
    ("LocalCredentialProvider", "Watches the local credential store and loads the stored plugin credentials."),
    ("RemoteCredentialProvider", "Follows the credential server connection settings and connects when they change."),
    ("PluginManager", "Loads the eight built-in plugins (Tidal, Youtube, Soundcloud, Gamdl, MusicBrainz, Recommendation, Subsonic, PodcastIndex), then every jar in plugins/."),
    ("ImporterState", "Starts the import log buffer behind the importer page."),
    ("ImportService", "Resumes the import jobs in JobService."),
    ("StorageService", "Computes the cached storage sizes."),
    ("CoverAssetPackService", "Loads the built-in cover asset pack and the packs found in the assets directory."),
    ("CoverGenerationService", "Registers itself with ImageService as the recoverer of generated images."),
    ("CoverAutoTrigger", "Subscribes to PlaylistChanged and CollectionChanged when covers.autoGenerate is on."),
    ("HueService", "Hue bridges. Subscribes to NowPlayingChanged."),
]


def build(root):
    nodes = []
    edges = {}

    def link(source, target, kind, label, flow):
        edge = edges.setdefault((source, target, kind), {"from": source, "to": target, "label": label, "kind": kind, "flows": []})
        edge["flows"].append(flow)

    order = {step: index + 1 for index, step in enumerate(SEQUENCE)}
    by_id = {}
    for node_id, label, sub, group, desc, src in STEPS:
        meta = [["Step", str(order[node_id])]] if node_id in order else []
        by_id[node_id] = src
        nodes.append({"id": node_id, "label": label, "sub": sub, "group": group, "desc": desc, "meta": meta, "src": src})

    for earlier, later in zip(SEQUENCE, SEQUENCE[1:]):
        flow = {"title": "Next step", "transport": "sequential call in Application.module", "carries": "control passes on when the earlier step returned", "src": by_id[later]}
        if earlier == "setup-check":
            flow["trigger"] = "no setup source is configured, the database is not empty, the backup file does not exist, or the mirror username or password is missing"
        link(earlier, later, "then", "then", flow)

    nodes += [
        {"id": "halt-refused", "label": "halt(1)", "sub": "database not at the base", "group": "exit",
         "desc": "The refusal names the image ghcr.io/dertyp7214/synara:0.0.1-dev to start once, then the process ends with exit code 1.", "meta": [], "src": APP + ":119"},
        {"id": "halt-setup-done", "label": "halt(0)", "sub": "setup finished", "group": "exit",
         "desc": "The process ends with exit code 0 after a setup from backup or mirror. The log line says Restarting server, the restart itself is left to whatever supervises the process.", "meta": [], "src": APP + ":147"},
        {"id": "halt-setup-failed", "label": "halt(1)", "sub": "backup schema refused", "group": "exit",
         "desc": "loadBackup threw BackupSchemaException. The message is logged and the process ends with exit code 1. Any other exception is not caught here.", "meta": [], "src": APP + ":144"},
        {"id": "background-maintenance", "label": "Background maintenance", "sub": "one launch, three calls", "group": "background",
         "desc": "cleanupRunningLogs removes task log rows left in RUNNING. CustomMigrationService.runMigrations runs the custom migrations it finds in the package dev.dertyp.migrations.custom, which holds none in the main sources at present. SqliteForeignKeyCheck runs PRAGMA foreign_key_check on SQLite. The three run in one coroutine on Dispatchers.IO, and the steps after the launch do not wait for it.",
         "meta": [], "src": APP + ":190"},
        {"id": "link-resolver-refresh", "label": "LinkResolver refresh", "sub": "supported platforms", "group": "background",
         "desc": "LinkResolverService.refreshSupported loads the list of supported platforms. It returns at once when the link resolver credential is not available.", "meta": [], "src": APP + ":209"},
        {"id": "metrics-flush", "label": "RPC metrics flush loop", "sub": "when metrics are enabled", "group": "background",
         "desc": "RpcMetricsCollector.runFlushLoop calls flush every metrics.flushIntervalSeconds, 60 by default.", "meta": [["Enabled by", "metrics.enabled"]], "src": APP + ":215"},
        {"id": "search-index-worker", "label": "SearchIndexWorker", "sub": "PostgreSQL only", "group": "services",
         "desc": "The search index loop is started only when the database driver is PostgreSQL.", "meta": [], "src": S + "Services.kt:45"},
    ]

    link("base-check", "halt-refused", "branch", "refused", {
        "title": "Database not at the base", "transport": "DatabaseNotAtBaseException, then Runtime.halt(1)",
        "carries": "the refusal reason is logged before the process ends", "trigger": "MigrationBaseCheck.refusal returns a refusal", "src": APP + ":117",
    })
    link("setup-check", "setup-backup", "branch", "from backup", {
        "title": "Setup from backup", "transport": "BackupService.loadBackup(file)", "carries": "the backup zip named by SETUP_FROM_BACKUP",
        "trigger": "SETUP_FROM_BACKUP is set, the file exists, no songs and at most one user", "config": "SETUP_FROM_BACKUP", "src": APP + ":134",
    })
    link("setup-check", "setup-mirror", "branch", "from mirror", {
        "title": "Setup from mirror", "transport": "RemoteMirrorService.startMirror", "carries": "the URL, username and password of the source server, as an import that includes users",
        "trigger": "SETUP_FROM_BACKUP is not set, SETUP_FROM_MIRROR_URL, username and password are set, no songs and at most one user",
        "config": "SETUP_FROM_MIRROR_URL, SETUP_FROM_MIRROR_USERNAME, SETUP_FROM_MIRROR_PASSWORD", "src": APP + ":151",
    })
    link("setup-backup", "halt-setup-done", "then", "then", {
        "title": "Backup loaded", "transport": "Runtime.halt(0)", "carries": "the process ends so that it restarts on the restored database", "src": APP + ":147",
    })
    link("setup-backup", "halt-setup-failed", "branch", "failed", {
        "title": "Backup failed", "transport": "Runtime.halt(1)", "carries": "the refusal message is logged", "trigger": "loadBackup throws BackupSchemaException", "src": APP + ":144",
    })
    link("setup-mirror", "halt-setup-done", "then", "then", {
        "title": "Mirror finished", "transport": "Runtime.halt(0)", "carries": "the process ends so that it restarts on the mirrored database", "src": APP + ":179",
    })
    link("setup-check", "background-maintenance", "async", "launches", {
        "title": "Maintenance launch", "transport": "ApplicationScope.scope.launch(Dispatchers.IO)",
        "carries": "cleanupRunningLogs, runMigrations and the SQLite foreign key check, in that order", "trigger": "after the setup check, before ensureDefaults", "src": APP + ":190",
    })
    link("schedule-start", "link-resolver-refresh", "async", "launches", {
        "title": "Supported platforms", "transport": "ApplicationScope.scope.launch(Dispatchers.IO)", "carries": "LinkResolverService.refreshSupported", "src": APP + ":208",
    })
    link("schedule-start", "metrics-flush", "async", "launches", {
        "title": "Metrics flush loop", "transport": "ApplicationScope.scope.launch(Dispatchers.IO)", "carries": "RpcMetricsCollector.runFlushLoop",
        "trigger": "metricsCollector.enabled", "config": "metrics.enabled", "src": APP + ":213",
    })

    for index, (name, desc) in enumerate(SERVICE_STARTS):
        node_id = "start-" + "".join("-" + c.lower() if c.isupper() else c for c in name).strip("-")
        nodes.append({
            "id": node_id, "label": name, "sub": "start " + str(index + 1) + " of " + str(len(SERVICE_STARTS)), "group": "services",
            "desc": desc, "meta": [], "src": S + "Services.kt:" + str(35 + index),
        })
        link("configure-services", node_id, "async", "launches", {
            "title": name, "transport": "ServiceLifecycle.start, a launch on ApplicationScope",
            "carries": "startService runs in its own coroutine, a failure is logged and does not stop the others", "src": S + "services/ServiceLifecycle.kt:24",
        })
    link("configure-services", "search-index-worker", "branch", "on PostgreSQL", {
        "title": "Search index loop", "transport": "SearchIndexWorker.startService(ApplicationScope.scope)", "carries": "starts the queue polling loop",
        "trigger": "the database driver is PostgreSQL", "src": S + "Services.kt:45",
    })

    nodes += [
        {"id": "stopping", "label": "ApplicationStopping", "sub": "Ktor event", "group": "shutdown",
         "desc": "The shutdown sequence subscribed by configureShutdown.", "meta": [], "src": APP + ":228"},
        {"id": "stop-all", "label": "ServiceLifecycle.stopAll", "sub": "reverse order, 10 s each", "group": "shutdown",
         "desc": "Stops the registered services in reverse registration order. Each stop has 10 seconds, after which a warning is logged and the next one is stopped. A Service gets stopService, an AutoCloseable gets close.", "meta": [], "src": S + "services/ServiceLifecycle.kt:41"},
        {"id": "cancel-scope", "label": "Cancel ApplicationScope", "sub": "background coroutines end", "group": "shutdown",
         "desc": "Cancels the scope that all background launches run on.", "meta": [], "src": APP + ":235"},
        {"id": "kill-children", "label": "killAll", "sub": "child processes", "group": "shutdown",
         "desc": "Kills the command-line tools the server started.", "meta": [], "src": APP + ":240"},
    ]
    link("configure-shutdown", "stopping", "branch", "on stop", {
        "title": "Shutdown subscription", "transport": "monitor.subscribe(ApplicationStopping)", "carries": "the sequence below runs when the application stops",
        "trigger": "the application is stopping", "src": APP + ":228",
    })
    for earlier, later, src in [("stopping", "stop-all", APP + ":230"), ("stop-all", "cancel-scope", APP + ":235"), ("cancel-scope", "kill-children", APP + ":240")]:
        link(earlier, later, "then", "then", {
            "title": "Next step", "transport": "sequential, each in its own try", "carries": "a failure is logged and the next step still runs", "src": src,
        })

    return {
        "id": "startup",
        "tab": "Startup",
        "noun": "step",
        "blurb": "What Application.module does from process start to serving, top to bottom, and what runs when the application stops. Dotted arrows are launches the following steps do not wait for.",
        "out": "Continues with",
        "inn": "Comes after",
        "layout": {"kind": "columns", "ordered": True},
        "groups": GROUPS,
        "styles": STYLES,
        "nodes": nodes,
        "edges": [edges[key] for key in sorted(edges)],
        "notes": [
            {"title": "Startup exits use halt", "text": "A refused database ends the process with exit code 1. A backup refused by BackupSchemaException ends it with exit code 1 as well. A finished setup from backup or mirror ends it with exit code 0, and the code does not restart the process itself.", "src": APP + ":119"},
            {"title": "Custom migrations run in a background launch", "text": "CustomMigrationService.runMigrations is launched on ApplicationScope together with the log cleanup and the SQLite foreign key check. The steps that follow in the module do not wait for the launch.", "src": APP + ":190"},
            {"title": "Service starts are launches", "text": "ServiceLifecycle.start launches startService on ApplicationScope and returns. PluginManager, which loads the plugins and mounts their routes, is started this way after configureRouting.", "src": S + "services/ServiceLifecycle.kt:24"},
            {"title": "Subscribers register before the database exists", "text": "configureHooks runs right after Koin is installed and before DatabaseManager.init.", "src": APP + ":109"},
        ],
    }
