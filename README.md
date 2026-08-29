# Synara API

Synara is a powerful, modern music server and API designed for high-fidelity audio enthusiasts. It provides a robust backend for indexing, managing, and streaming your music library with support for external services like Tidal and Spotify.

## Key Features

- **High-Fidelity Audio**: Native support for `FLAC` with transcoding to `Opus` for efficient streaming and downloads.
- **Service Integrations**:
    - **Tidal**: Metadata fetching, favorites synchronization, and integrated downloading support. Dolby Atmos tracks are imported as a 5.1 lossless file plus a streamable Atmos (E-AC-3 JOC) variant (`streamSongAtmos`, API version 3+). From API version 4, file properties are nested in `audio` / `atmos` (`AudioInfo`: codec, sampleRate, bitsPerSample, bitRate, fileSize, channels). Existing tiddl volumes need `atmos_filter = "allow"` under `[download]` in `config.toml`.
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

- **API Reference**: Swagger documentation is available at `/swagger` when the server is running.
- **RPC Services**: Detailed breakdown of available RPC interfaces can be found in [docs/RPC_SERVICES.md](docs/RPC_SERVICES.md).
- **Configuration**: See [docs/ENVIRONMENT_VARIABLES.md](docs/ENVIRONMENT_VARIABLES.md) for a full list of configuration options and defaults.
- **Plugins**: Learn how to extend Synara with custom [plugins](docs/PLUGINS.md).
- **Server-Driven UI**: How clients render [server-described UI](docs/SERVER_DRIVEN_UI.md) (slots, pages, home cards, hooks), with the importer as worked example.
- **Mock Server**: Use the [mock server](docs/MOCK_SERVER.md) for development and testing.
- **Listen Backup**: Keep a copy of listening history on a [remote listen-backup receiver](docs/LISTEN_BACKUP.md).
- **Development Instructions**: Detailed developer guide in [docs/INSTRUCTIONS.md](docs/INSTRUCTIONS.md).

## Getting Started

### Prerequisites

- Java 21 or higher (Amazon Corretto 25 recommended for production).
- (Optional) Docker and Docker Compose.

### Running the Server

| Method     | Command                 |
|:-----------|:------------------------|
| **Gradle** | `./gradlew :server:run` |
| **Docker** | `docker compose up`     |

### Development Setup

A helper script `dev.sh` is provided to quickly build and run both the server and the proxy components in a development environment.

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
