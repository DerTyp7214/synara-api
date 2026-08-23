plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.rpc) apply false
    alias(libs.plugins.buildconfig) apply false

    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kover) apply false
}

subprojects {
    group = "dev.dertyp"
    version = "0.0.1"
}

tasks.register("generateEnvDocs") {
    group = "documentation"
    description = "Generates example.env and docs/ENVIRONMENT_VARIABLES.md from application.yaml files"

    val yamlFiles = listOf(
        file("server/src/main/resources/application.yaml"),
        file("proxy/src/main/resources/application.yaml")
    )
    val dockerfile = file("Dockerfile.nobuild")
    val envFile = file("example.env")
    val docsFile = file("docs/ENVIRONMENT_VARIABLES.md")

    inputs.files(yamlFiles, dockerfile)
    outputs.files(envFile, docsFile)

    doLast {
        val varInfo = mapOf(
            "PORT" to mapOf("desc" to "The port the server listens on.", "cat" to "General & Authentication"),
            "CLIENT_ID" to mapOf("desc" to "Initial admin username (only on first run).", "cat" to "General & Authentication"),
            "CLIENT_SECRET" to mapOf("desc" to "Initial admin password (only on first run).", "cat" to "General & Authentication"),
            "JWT_ISSUER" to mapOf("desc" to "The issuer claim for JWT tokens.", "cat" to "General & Authentication"),
            "JWT_AUDIENCE" to mapOf("desc" to "The audience claim for JWT tokens.", "cat" to "General & Authentication"),
            "JWT_REALM" to mapOf("desc" to "The realm for JWT authentication.", "cat" to "General & Authentication"),
            "JWT_SECRET" to mapOf("desc" to "Secret key for signing tokens.", "cat" to "General & Authentication"),
            "DB_DRIVER" to mapOf("desc" to "JDBC driver class name.", "cat" to "Database Configuration"),
            "DB_URL" to mapOf("desc" to "JDBC connection URL.", "cat" to "Database Configuration"),
            "DB_USER" to mapOf("desc" to "Database username.", "cat" to "Database Configuration"),
            "DB_PASSWORD" to mapOf("desc" to "Database password.", "cat" to "Database Configuration"),
            "BACKUP_DIR" to mapOf("desc" to "Directory for database backups.", "cat" to "Database Configuration"),
            "SETUP_FROM_BACKUP" to mapOf("desc" to "Path to a backup file to initialize the server from (only if the database is empty).", "cat" to "Database Configuration"),
            "SETUP_FROM_MIRROR_URL" to mapOf("desc" to "URL of another Synara server to initialize from (only if the database is empty).", "cat" to "Database Configuration"),
            "SETUP_FROM_MIRROR_USERNAME" to mapOf("desc" to "Username for the remote server mirroring setup.", "cat" to "Database Configuration"),
            "SETUP_FROM_MIRROR_PASSWORD" to mapOf("desc" to "Password for the remote server mirroring setup.", "cat" to "Database Configuration"),
            "REDIS_HOST" to mapOf("desc" to "Redis server hostname.", "cat" to "Redis Configuration"),
            "REDIS_PORT" to mapOf("desc" to "Redis server port.", "cat" to "Redis Configuration"),
            "REDIS_USE_SEARCH" to mapOf("desc" to "Whether to use RediSearch for ranked searching.", "cat" to "Redis Configuration"),
            "REDIS_INDEX_PREFIX" to mapOf("desc" to "Prefix for Redis Search indices.", "cat" to "Redis Configuration"),
            "REDIS_CACHE_ANIMATED_IMAGES" to mapOf("desc" to "Whether to cache animated cover bytes in Redis.", "cat" to "Redis Configuration"),
            "IMAGE_CACHE_URL" to mapOf("desc" to "Image Cache service URL.", "cat" to "External Services"),
            "IMAGE_CACHE_TOKEN" to mapOf("desc" to "Image Cache auth token.", "cat" to "External Services"),
            "TRANSCRIBER_URL" to mapOf("desc" to "Transcriber service URL.", "cat" to "External Services"),
            "AUDIO_EMBED_URL" to mapOf("desc" to "URL of the audio embedding service (MusiCNN). Empty disables per-song audio embeddings.", "cat" to "Recommendations"),
            "RECSYS_DATA_DIR" to mapOf("desc" to "Shared directory used to exchange training data with the recommendation (recsys) service. Empty disables recommendation training.", "cat" to "Recommendations"),
            "LINKRESOLVER_API_KEY" to mapOf("desc" to "API key for the self-hosted LinkResolver service (linkresolver.synara.audio).", "cat" to "External Services"),
            "SPOTIFY_CLIENT_ID" to mapOf("desc" to "Spotify API Client ID.", "cat" to "External Services"),
            "SPOTIFY_CLIENT_SECRET" to mapOf("desc" to "Spotify API Client Secret.", "cat" to "External Services"),
            "TIDAL_CLIENT_ID" to mapOf("desc" to "Tidal API Client ID.", "cat" to "External Services"),
            "TIDAL_CLIENT_SECRET" to mapOf("desc" to "Tidal API Client Secret.", "cat" to "External Services"),
            "APPLE_MUSIC_TEAM_ID" to mapOf("desc" to "Apple Music Team ID.", "cat" to "External Services"),
            "APPLE_MUSIC_KEY_ID" to mapOf("desc" to "Apple Music Key ID.", "cat" to "External Services"),
            "APPLE_MUSIC_P8_PATH" to mapOf("desc" to "Path to the Apple Music .p8 private key file.", "cat" to "External Services"),
            "AUDIO_TRACKS_PATH" to mapOf("desc" to "Base path for audio tracks.", "cat" to "Storage & Paths"),
            "AUDIO_ALBUMS_PATH" to mapOf("desc" to "Base path for albums.", "cat" to "Storage & Paths"),
            "AUDIO_PLAYLISTS_PATH" to mapOf("desc" to "Base path for playlists.", "cat" to "Storage & Paths"),
            "AUDIO_TRANSCODE_PATH" to mapOf("desc" to "Path for transcoded files.", "cat" to "Storage & Paths"),
            "AUDIO_AUTO_TRANSCODE_QUALITIES" to mapOf("desc" to "Bitrates for auto-transcoding (Opus).", "cat" to "Storage & Paths"),
            "AUDIO_AUTO_TRANSCODE_QUALITIES_AAC" to mapOf("desc" to "Bitrates for auto-transcoding (AAC).", "cat" to "Storage & Paths"),
            "AUDIO_CUSTOM_PATH" to mapOf("desc" to "Path for custom uploaded audio.", "cat" to "Storage & Paths"),
            "AUDIO_LOSSLESS_FORMAT" to mapOf("desc" to "Lossless container for audio the server produces (custom uploads, downloader imports): FLAC, WAV or AIFF.", "cat" to "Storage & Paths"),
            "DATA_IMAGES_PATH" to mapOf("desc" to "Path for cached images/covers.", "cat" to "Storage & Paths"),
            "DATA_ANIMATED_IMAGES_PATH" to mapOf("desc" to "Path for cached animated covers.", "cat" to "Storage & Paths"),
            "AUDIO_TRACKS_SECONDARY_PATH" to mapOf("desc" to "Optional secondary audio path.", "cat" to "Storage & Paths"),
            "YTDLP_CONFIG_PATH" to mapOf("desc" to "Path to yt-dlp.conf for yt-dlp.", "cat" to "Storage & Paths"),
            "GAMDL_COOKIES_PATH" to mapOf("desc" to "Path to the Netscape cookies.txt for Apple Music (gamdl importer).", "cat" to "External Services"),
            "GAMDL_WVD_PATH" to mapOf("desc" to "Optional path to a Widevine .wvd device for higher-quality gamdl codecs.", "cat" to "External Services"),
            "GAMDL_CODEC_SONG" to mapOf("desc" to "Optional gamdl song codec override (empty = gamdl default, e.g. aac-web). Advanced/version-sensitive.", "cat" to "External Services"),
            "PROXY_HOSTNAME" to mapOf("desc" to "Public hostname of the proxy.", "cat" to "Proxy Configuration"),
            "PROXY_CONTROL_PORT" to mapOf("desc" to "Port for the proxy control interface.", "cat" to "Proxy Configuration"),
            "PROXY_SSL" to mapOf("desc" to "Whether to use SSL (true/false).", "cat" to "Proxy Configuration"),
            "PROXY_NAME" to mapOf("desc" to "Display name for this proxy instance.", "cat" to "Proxy Configuration"),
            "PROXY_ID" to mapOf("desc" to "Unique identifier for this proxy.", "cat" to "Proxy Configuration"),
            "PROXY_KEY" to mapOf("desc" to "Authentication key for the proxy.", "cat" to "Proxy Configuration"),
            "YOUTUBE_API_KEY" to mapOf("desc" to "Youtube API key for YouTube Data API v3 (Downloader).", "cat" to "Other"),
            "WORKER_THREAD_MULTIPLIER" to mapOf("desc" to "Multiplier for background worker threads. Scales the number of parallel tasks relative to CPU cores.", "cat" to "Other"),
            "METRICS_ENABLED" to mapOf("desc" to "Whether to collect RPC call usage metrics (invocation counts per call, per user, over time).", "cat" to "Metrics"),
            "METRICS_FLUSH_INTERVAL_SECONDS" to mapOf("desc" to "How often (in seconds) buffered in-memory call counts are flushed to the database.", "cat" to "Metrics"),
            "METRICS_EVENT_LOG_RETENTION_HOURS" to mapOf("desc" to "How long (in hours) individual call events are retained in the capped event log before pruning. Lifetime totals are never pruned.", "cat" to "Metrics")
        )

        val varsFound = mutableMapOf<String, String?>()
        val pattern = Regex("\\$\\{([^}:]+)(:([^}]*))?}")

        yamlFiles.forEach { file ->
            if (file.exists()) {
                pattern.findAll(file.readText()).forEach { match ->
                    val varName = match.groupValues[1]
                    val hasColon = match.groupValues[2].startsWith(":")
                    val defaultVal = if (hasColon) match.groupValues[3] else null
                    if (!varsFound.containsKey(varName)) {
                        varsFound[varName] = defaultVal
                    }
                }
            }
        }

        val dockerDefaults = mutableMapOf<String, String>()
        if (dockerfile.exists()) {
            val dockerPattern = Regex("ENV\\s+([A-Z0-9_]+)=\"([^\"]+)\"")
            dockerPattern.findAll(dockerfile.readText()).forEach { match ->
                dockerDefaults[match.groupValues[1]] = match.groupValues[2]
            }
        }

        val categories = mutableMapOf<String, MutableList<Triple<String, String?, String>>>()
        varsFound.forEach { (varName, defaultVal) ->
            val info = varInfo[varName] ?: mapOf("desc" to "", "cat" to "Other")
            val cat = info["cat"] ?: "Other"
            categories.getOrPut(cat) { mutableListOf() }.add(Triple(varName, defaultVal, info["desc"] ?: ""))
        }

        // Generate .env
        val envContent = StringBuilder()
        categories.keys.sorted().forEach { cat ->
            envContent.append("# --- $cat ---\n")
            categories[cat]?.sortedBy { it.first }?.forEach { (varName, defaultVal, desc) ->
                if (desc.isNotEmpty()) envContent.append("# $desc\n")
                val displayVal = defaultVal ?: ""
                val value = if (displayVal.contains(" ") || displayVal.contains(":")) "\"$displayVal\"" else displayVal
                envContent.append("$varName=$value\n")
            }
            envContent.append("\n")
        }
        envFile.writeText(envContent.toString().trim() + "\n")

        // Generate Markdown
        val docContent = StringBuilder()
        docContent.append("# Environment Variables Documentation\n\n")
        docContent.append("This document describes the environment variables used by the Synara API and Proxy, based on the `application.yaml` configuration.\n\n")
        docContent.append("## Getting Started\n\n")
        docContent.append("You can define these variables in a `.env` file in the root of the project. An example file is provided in [`example.env`](../example.env).\n\n")
        docContent.append("Most variables have sensible defaults and are **optional**. Only variables without a default value in `application.yaml` (no `:` suffix) are strictly required to be provided by the environment.\n\n")
        docContent.append("### Default Precedence\n\n")
        docContent.append("1. **Environment Variables**: Any variable set in your environment (or `.env` file) takes highest precedence.\n")
        docContent.append("2. **Docker Defaults**: If running via Docker, variables set in the `Dockerfile` override standalone defaults.\n")
        docContent.append("3. **Standalone Defaults**: The default values defined in `application.yaml` are used if no other value is provided.\n\n")

        categories.keys.sorted().forEach { cat ->
            docContent.append("## $cat\n\n")
            docContent.append("| Variable | Description | Required | Default (Standalone) | Default (Docker) |\n")
            docContent.append("|:---|:---|:---:|:---|:---|\n")
            categories[cat]?.sortedBy { it.first }?.forEach { (varName, defaultVal, desc) ->
                val required = if (defaultVal == null) "Yes" else "No"
                val defaultDisplay = if (defaultVal.isNullOrEmpty()) "-" else "`$defaultVal`"
                val dockerDisplay = dockerDefaults[varName]?.let { "`$it`" } ?: "-"
                docContent.append("| `$varName` | $desc | $required | $defaultDisplay | $dockerDisplay |\n")
            }
            docContent.append("\n")
        }
        docsFile.writeText(docContent.toString())

        println("Successfully generated example.env and docs/ENVIRONMENT_VARIABLES.md")
    }
}

tasks.register<Exec>("installGitHooks") {
    group = "help"
    description = "Configures git to use the hooks in the .githooks directory"
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast {
        println("Git hooks path updated to .githooks")
    }
}
