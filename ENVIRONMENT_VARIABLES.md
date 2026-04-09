# Environment Variables Documentation

This document describes the environment variables used by the Synara API and Proxy, based on the `application.yaml` configuration.

## Getting Started

You can define these variables in a `.env` file in the root of the project. An example file is provided in [`example.server.env`](example.server.env).

Most variables have sensible defaults and are **optional**. Only variables without a default value in `application.yaml` are strictly required to be provided by the environment.

## General & Authentication

| Variable        | Description                                 | Required | Default                  |
|:----------------|:--------------------------------------------|:--------:|:-------------------------|
| `PORT`          | The port the server listens on.             |    No    | `8080`                   |
| `CLIENT_ID`     | Initial admin username (only on first run). |    No    | -                        |
| `CLIENT_SECRET` | Initial admin password (only on first run). |    No    | -                        |
| `JWT_ISSUER`    | The issuer claim for JWT tokens.            |    No    | `synara`                 |
| `JWT_AUDIENCE`  | The audience claim for JWT tokens.          |    No    | `synara-api`             |
| `JWT_REALM`     | The realm for JWT authentication.           |    No    | `Access to 'Synara-API'` |
| `JWT_SECRET`    | Secret key for signing tokens.              |    No    | `changeme`               |

## Database Configuration

| Variable      | Description                     | Required | Default               |
|:--------------|:--------------------------------|:--------:|:----------------------|
| `DB_DRIVER`   | JDBC driver class name.         |    No    | `org.sqlite.JDBC`     |
| `DB_URL`      | JDBC connection URL.            |    No    | `jdbc:sqlite:data.db` |
| `DB_USER`     | Database username.              |    No    | -                     |
| `DB_PASSWORD` | Database password.              |    No    | -                     |
| `BACKUP_DIR`  | Directory for database backups. |    No    | -                     |

## Storage & Paths

The application uses local folder defaults. When using the Docker image, these are pre-configured to point into the `/data` volume (see Docker column).

| Variable                         | Description                     | Required | Default (Standalone) | Default (Docker Image)  |
|:---------------------------------|:--------------------------------|:--------:|:---------------------|:------------------------|
| `AUDIO_TRACKS_PATH`              | Base path for audio tracks.     |    No    | `music/tracks`       | `/data/Tidal/Tracks`    |
| `AUDIO_ALBUMS_PATH`              | Base path for albums.           |    No    | `music/albums`       | `/data/Tidal/Albums`    |
| `AUDIO_PLAYLISTS_PATH`           | Base path for playlists.        |    No    | `music/playlists`    | `/data/Tidal/Playlists` |
| `AUDIO_TRANSCODE_PATH`           | Path for transcoded files.      |    No    | `music/transcode`    | `/data/Tidal/Transcode` |
| `AUDIO_CUSTOM_PATH`              | Path for custom uploaded audio. |    No    | `music/custom`       | `/data/Synara/custom`   |
| `DATA_IMAGES_PATH`               | Path for cached images/covers.  |    No    | `data/images`        | `/data/Tidal/Images`    |
| `AUDIO_TRACKS_SECONDARY_PATH`    | Optional secondary audio path.  |    No    | -                    | `/data/Synara`          |
| `AUDIO_AUTO_TRANSCODE_QUALITIES` | Bitrates for auto-transcoding.  |    No    | -                    | -                       |

## External Services

All external service credentials are **optional**.

| Variable                | Description                | Required | Default                 |
|:------------------------|:---------------------------|:--------:|:------------------------|
| `SPOTIFY_CLIENT_ID`     | Spotify API Client ID.     |    No    | -                       |
| `SPOTIFY_CLIENT_SECRET` | Spotify API Client Secret. |    No    | -                       |
| `TIDAL_CLIENT_ID`       | Tidal API Client ID.       |    No    | -                       |
| `TIDAL_CLIENT_SECRET`   | Tidal API Client Secret.   |    No    | -                       |
| `IMAGE_CACHE_URL`       | Image Cache service URL.   |    No    | -                       |
| `IMAGE_CACHE_TOKEN`     | Image Cache auth token.    |    No    | -                       |
| `TRANSCRIBER_URL`       | Transcriber service URL.   |    No    | `http://localhost:8000` |

## Redis Configuration

| Variable     | Description            | Required | Default |
|:-------------|:-----------------------|:--------:|:--------|
| `REDIS_HOST` | Redis server hostname. |    No    | -       |
| `REDIS_PORT` | Redis server port.     |    No    | -       |

## Proxy Configuration

Variables used specifically when running the Synara Proxy.

| Variable             | Description                           | Required | Default |
|:---------------------|:--------------------------------------|:--------:|:--------|
| `PROXY_HOSTNAME`     | Public hostname of the proxy.         |    No    | -       |
| `PROXY_CONTROL_PORT` | Port for the proxy control interface. |    No    | `8081`  |
| `PROXY_SSL`          | Whether to use SSL (`true`/`false`).  |    No    | `false` |
| `PROXY_NAME`         | Display name for this proxy instance. |    No    | -       |
| `PROXY_ID`           | Unique identifier for this proxy.     |    No    | -       |
| `PROXY_KEY`          | Authentication key for the proxy.     |    No    | -       |
