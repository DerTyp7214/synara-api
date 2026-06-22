# Environment Variables Documentation

This document describes the environment variables used by the Synara API and Proxy, based on the `application.yaml` configuration.

## Getting Started

You can define these variables in a `.env` file in the root of the project. An example file is provided in [`example.env`](../example.env).

Most variables have sensible defaults and are **optional**. Only variables without a default value in `application.yaml` (no `:` suffix) are strictly required to be provided by the environment.

### Default Precedence

1. **Environment Variables**: Any variable set in your environment (or `.env` file) takes highest precedence.
2. **Docker Defaults**: If running via Docker, variables set in the `Dockerfile` override standalone defaults.
3. **Standalone Defaults**: The default values defined in `application.yaml` are used if no other value is provided.

## Database Configuration

| Variable | Description | Required | Default (Standalone) | Default (Docker) |
|:---|:---|:---:|:---|:---|
| `BACKUP_DIR` | Directory for database backups. | No | - | `/backups` |
| `DB_DRIVER` | JDBC driver class name. | No | `org.sqlite.JDBC` | - |
| `DB_PASSWORD` | Database password. | No | - | - |
| `DB_URL` | JDBC connection URL. | No | `jdbc:sqlite:data.db` | - |
| `DB_USER` | Database username. | No | - | - |
| `SETUP_FROM_BACKUP` | Path to a backup file to initialize the server from (only if the database is empty). | No | - | - |
| `SETUP_FROM_MIRROR_PASSWORD` | Password for the remote server mirroring setup. | No | - | - |
| `SETUP_FROM_MIRROR_URL` | URL of another Synara server to initialize from (only if the database is empty). | No | - | - |
| `SETUP_FROM_MIRROR_USERNAME` | Username for the remote server mirroring setup. | No | - | - |

## External Services

| Variable | Description | Required | Default (Standalone) | Default (Docker) |
|:---|:---|:---:|:---|:---|
| `APPLE_MUSIC_KEY_ID` | Apple Music Key ID. | No | - | - |
| `APPLE_MUSIC_P8_PATH` | Path to the Apple Music .p8 private key file. | No | - | - |
| `APPLE_MUSIC_TEAM_ID` | Apple Music Team ID. | No | - | - |
| `IMAGE_CACHE_TOKEN` | Image Cache auth token. | No | - | - |
| `IMAGE_CACHE_URL` | Image Cache service URL. | No | - | - |
| `LINKRESOLVER_API_KEY` | API key for the self-hosted LinkResolver service (linkresolver.synara.audio). | No | - | - |
| `SPOTIFY_CLIENT_ID` | Spotify API Client ID. | No | - | - |
| `SPOTIFY_CLIENT_SECRET` | Spotify API Client Secret. | No | - | - |
| `TIDAL_CLIENT_ID` | Tidal API Client ID. | No | - | - |
| `TIDAL_CLIENT_SECRET` | Tidal API Client Secret. | No | - | - |
| `TRANSCRIBER_URL` | Transcriber service URL. | No | `http://localhost:8000` | - |

## General & Authentication

| Variable | Description | Required | Default (Standalone) | Default (Docker) |
|:---|:---|:---:|:---|:---|
| `CLIENT_ID` | Initial admin username (only on first run). | No | - | - |
| `CLIENT_SECRET` | Initial admin password (only on first run). | No | - | - |
| `JWT_AUDIENCE` | The audience claim for JWT tokens. | No | `synara-api` | - |
| `JWT_ISSUER` | The issuer claim for JWT tokens. | No | `synara` | - |
| `JWT_REALM` | The realm for JWT authentication. | No | `Access to 'Synara-API'` | - |
| `JWT_SECRET` | Secret key for signing tokens. | No | `changeme` | - |
| `PORT` | The port the server listens on. | No | `8080` | - |

## Other

| Variable | Description | Required | Default (Standalone) | Default (Docker) |
|:---|:---|:---:|:---|:---|
| `SERVER_SSL_SUPPORTED` |  | No | `false` | - |
| `WORKER_THREAD_MULTIPLIER` | Multiplier for background worker threads. Scales the number of parallel tasks relative to CPU cores. | No | `1.0` | - |
| `YOUTUBE_API_KEY` | Youtube API key for YouTube Data API v3 (Downloader). | No | - | - |

## Proxy Configuration

| Variable | Description | Required | Default (Standalone) | Default (Docker) |
|:---|:---|:---:|:---|:---|
| `PROXY_CONTROL_PORT` | Port for the proxy control interface. | No | `8081` | - |
| `PROXY_HOSTNAME` | Public hostname of the proxy. | No | - | - |
| `PROXY_ID` | Unique identifier for this proxy. | No | - | - |
| `PROXY_KEY` | Authentication key for the proxy. | No | - | - |
| `PROXY_NAME` | Display name for this proxy instance. | No | - | - |
| `PROXY_SSL` | Whether to use SSL (true/false). | No | `false` | - |

## Redis Configuration

| Variable | Description | Required | Default (Standalone) | Default (Docker) |
|:---|:---|:---:|:---|:---|
| `REDIS_CACHE_ANIMATED_IMAGES` | Whether to cache animated cover bytes in Redis. | No | `false` | - |
| `REDIS_HOST` | Redis server hostname. | No | - | - |
| `REDIS_INDEX_PREFIX` | Prefix for Redis Search indices. | No | `synara` | - |
| `REDIS_PORT` | Redis server port. | No | - | - |
| `REDIS_USE_SEARCH` | Whether to use RediSearch for ranked searching. | No | `false` | - |

## Storage & Paths

| Variable | Description | Required | Default (Standalone) | Default (Docker) |
|:---|:---|:---:|:---|:---|
| `AUDIO_ALBUMS_PATH` | Base path for albums. | No | `music/albums` | `/data/Synara/Albums` |
| `AUDIO_AUTO_TRANSCODE_QUALITIES` | Bitrates for auto-transcoding (Opus). | No | - | - |
| `AUDIO_AUTO_TRANSCODE_QUALITIES_AAC` | Bitrates for auto-transcoding (AAC). | No | - | - |
| `AUDIO_CUSTOM_PATH` | Path for custom uploaded audio. | No | `music/custom` | `/data/Synara/custom` |
| `AUDIO_PLAYLISTS_PATH` | Base path for playlists. | No | `music/playlists` | `/data/Synara/Playlists` |
| `AUDIO_TRACKS_PATH` | Base path for audio tracks. | No | `music/tracks` | `/data/Synara/Tracks` |
| `AUDIO_TRACKS_SECONDARY_PATH` | Optional secondary audio path. | No | - | `/data/Synara/other` |
| `AUDIO_TRANSCODE_PATH` | Path for transcoded files. | No | `music/transcode` | `/data/Synara/Transcode` |
| `DATA_ANIMATED_IMAGES_PATH` | Path for cached animated covers. | No | `data/animated-images` | `/data/Synara/AnimatedImages` |
| `DATA_IMAGES_PATH` | Path for cached images/covers. | No | `data/images` | `/data/Synara/Images` |
| `YTDLP_CONFIG_PATH` | Path to yt-dlp.conf for yt-dlp. | No | - | `/data/config/yt-dlp.conf` |

