# Synara API

Synara is a powerful, modern music server and API designed for high-fidelity audio enthusiasts. It provides a robust backend for indexing, managing, and streaming your music library with support for external services like Tidal and Spotify.

## Key Features

- **High-Fidelity Audio**: Native support for `FLAC` with transcoding to `Opus` for efficient streaming and downloads.
- **Service Integrations**:
    - **Tidal**: Metadata fetching, favorites synchronization, integrated downloading, and Dolby Atmos import; see [docs/API_VERSIONING.md](docs/API_VERSIONING.md) for how clients opt into Atmos streaming and newer response shapes.
    - **Spotify**: Metadata resolution and artist/album matching.
    - **MusicBrainz**: Comprehensive metadata enrichment and persistent identifiers.
- **Advanced Library Management**:
    - Automatic indexing of local media.
    - Intelligent artist management (merging, splitting, and aliasing).
    - Global and user-specific playlist support.
    - Synced and unsynced lyrics search.
- **Modern Communication**: Built on `kotlinx.rpc` and Ktor, utilizing **CBOR** for high-performance, type-safe RPC communication.
- **Multi-Device Sync**: Playback state synchronization and session management across all your devices.
- **Extensible Architecture**: Includes built-in support for reverse proxies, image caching, and scheduled maintenance tasks.
- **Admin Tools**: Integrated backup/restore system and detailed server statistics.

## Documentation

The full index is at [docs/README.md](docs/README.md); Swagger/OpenAPI documentation is also available at `/swagger` when the server is running.

**Build a client**: [Getting Started](docs/CLIENT_GETTING_STARTED.md) · [Kotlin RPC](docs/CLIENT_KOTLIN_RPC.md) · [REST](docs/CLIENT_REST.md) · [Authentication](docs/AUTHENTICATION.md) · [Streaming & Playback](docs/STREAMING_AND_PLAYBACK.md) · [API Versioning](docs/API_VERSIONING.md) · [Server-Driven UI](docs/SERVER_DRIVEN_UI.md) · [Client Settings](docs/CLIENT_SETTINGS.md) · [Mock Server](docs/MOCK_SERVER.md)

**Reference (generated, do not edit — `./gradlew generateDocs` regenerates all of these)**: [RPC Services](docs/RPC_SERVICES.md) (`:common-rpc:kspCommonMainKotlinMetadata`) · [Models](docs/MODELS.md) (`:common-rpc:kspCommonMainKotlinMetadata`) · [Permissions](docs/PERMISSIONS.md) (`:common-rpc:kspCommonMainKotlinMetadata`) · [REST API](docs/REST_API.md) (`:server:kspKotlin`) · [API Constants](docs/API_CONSTANTS.md) (`:server:generateApiConstantsDocs`) · [Environment Variables](docs/ENVIRONMENT_VARIABLES.md) (`generateEnvDocs`)

**Extend the server**: [Plugins](docs/PLUGINS.md) · [MCP](docs/MCP.md) · [Listen Backup](docs/LISTEN_BACKUP.md)

**Work on the server**: [Architecture](docs/ARCHITECTURE.md) · [Development](docs/DEVELOPMENT.md)

## Writing a client

Pick a transport first: Kotlin/KMP apps use the typed RPC SDK ([docs/CLIENT_KOTLIN_RPC.md](docs/CLIENT_KOTLIN_RPC.md)), everything else talks plain HTTP/JSON ([docs/CLIENT_REST.md](docs/CLIENT_REST.md)) — both surfaces share the same services, models and conventions, so [docs/CLIENT_GETTING_STARTED.md](docs/CLIENT_GETTING_STARTED.md) is the right place to start either way. Point your client at a real server, or run [docs/MOCK_SERVER.md](docs/MOCK_SERVER.md) locally to develop against realistic dummy data without touching a music library.

## Getting Started

### Prerequisites

- Java 25 (see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)).
- (Optional) Docker and Docker Compose.

### Running the Server

| Method     | Command                 |
|:-----------|:------------------------|
| **Gradle** | `./gradlew :server:run` |
| **Docker** | `docker compose up`     |

### Development Setup

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the full developer workflow: cloning with submodules, configuration, tests, and the `common-rpc` submodule. As a quick start, `./dev.sh` builds and runs both the server and the proxy components together:

```bash
./dev.sh
```

## Technical Details

- **Transcoding**: Saves and streams as `Opus` to balance quality and bandwidth.
- **Database**: Supports SQLite (default) and PostgreSQL for larger deployments.

---

If the server starts successfully, you'll see:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
