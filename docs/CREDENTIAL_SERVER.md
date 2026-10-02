# Synara Credential Server

The credential server is a small standalone service that holds the third-party credentials a Synara setup needs and hands them to the consumers that were granted them. Consumers are Synara servers (importers and metadata providers) and the plugins running on them. A consumer only receives the credentials it has been granted, so a Synara instance no longer has to keep every API key and CLI login on its own disk.

It never stores end-user credentials. Per-user logins such as a user's own Tidal OAuth session, ListenBrainz tokens or Hue pairings stay on the Synara server.

## How it works

1. An admin registers a client. A client has an id and a secret, and a list of credential names it may read.
2. The consumer sends its client id and secret to `POST /token` and receives a short-lived ES256 JWT (15 minutes by default) that carries its grants.
3. The consumer uses the JWT as a bearer token to list and fetch the credentials it was granted.
4. Every change to a client's grants, its enabled flag or its secret, and every explicit revoke, bumps the client's token version. The server only accepts a JWT whose version matches the current one, so a revoked client loses access immediately and not when its token expires.

Credentials are encrypted at rest with AES-256-GCM. The master key comes from `CREDENTIAL_SERVER_MASTER_KEY` or from a generated key file. Client secrets are stored hashed and are shown exactly once, when they are created or rotated.

### Brokered tokens and plain keys

The server brokers tokens where the provider allows it, so the long-lived secret never leaves the server:

- **Tidal and Spotify**: the stored client id and secret are exchanged through the client credentials flow. Consumers receive an access token with its expiry. Tokens are cached until shortly before they expire.
- **Apple Music**: the server signs the developer token from the stored `.p8` key and caches it for twelve hours.
- **Plain API keys**: keys such as AcoustID, YouTube, Podcast Index, TheAudioDB, the link resolver and the image cache token are returned as they are.

### Importer files

Importer logins are handed out as files that the consumer writes next to the CLI tool before it runs.

- **tiddl**: the server stores the Tidal session (client id, client secret and refresh token entered by the admin). The client id and secret are optional for the `importer.tiddl` and `importer.tdn` presets because they default to the public client that tiddl and tidal-dl-ng ship with. A typed value or an already stored client wins over that default. A Synara server with `TIDDL_AUTH` set sends that client for tiddl sessions instead of the default whenever the fields are blank. On every hand-out it runs a refresh token grant, persists a rotated refresh token and renders a fresh `auth.json`. Consumers therefore always receive a valid token. If Tidal rejects the refresh token, the credential gets the status `NEEDS_LOGIN` and has to be logged in again.
- **Write-back**: tiddl may rotate its refresh token while it runs. A consumer with the write-back grant sends the changed file back through `PUT /credentials/{name}/files` together with the fingerprint it received. The server accepts it only if that fingerprint is still current and otherwise answers 409, so two consumers sharing one session cannot silently overwrite each other. The loser may need a new login.
- **gamdl**: cookies and the device file are stored as they are and can not be refreshed. The server reads the cookie expiry so the status can show that the cookies are expiring. They have to be replaced manually.

File credentials are never cached by consumers and are written with mode 0600.

## Credential names

| Name                      | Kind                     | Used for                                         |
|---------------------------|--------------------------|--------------------------------------------------|
| `tidal.api`               | brokered access token    | Tidal metadata requests.                         |
| `spotify.api`             | brokered access token    | Spotify metadata requests.                       |
| `applemusic.developer`    | brokered developer token | Apple Music requests.                            |
| `youtube.api`             | API key                  | YouTube Data API.                                |
| `acoustid.api`            | API key                  | AcoustID fingerprint lookups.                    |
| `podcastindex.api`        | API key and secret       | Podcast Index searches.                          |
| `theaudiodb.api`          | API key                  | TheAudioDB lookups.                              |
| `linkresolver.api`        | API key                  | Link resolver requests.                          |
| `imagecache.token`        | API key                  | Image cache access.                              |
| `importer.tiddl`          | files                    | Tidal importer login (refreshed on every fetch). |
| `importer.tdn`            | files                    | Tidal downloader login.                          |
| `importer.gamdl`          | files                    | Apple Music importer cookies and device file.    |
| `plugin:<pluginId>:<name>`| API key or key pair      | Secrets of a single plugin.                      |

### Plugin credentials

Plugins read and store their secrets through the credentials API of the plugin context. A secret named `<name>` of the plugin `<pluginId>` is stored under `plugin:<pluginId>:<name>`. A client only sees a plugin secret if it was granted exactly that name, so one plugin can never read the secrets of another.

## Running the server

### Using Gradle

```bash
./gradlew :credential-server:run
```

The server listens on port `8083` and stores its data in `credentials.db` (SQLite) in the working directory.

### Using Docker

```bash
docker build -t synara-credentials -f Dockerfile.credentials .
docker run -p 8083:8083 \
  -e CREDENTIAL_SERVER_ADMIN_KEY=change-me \
  -e CREDENTIAL_SERVER_MASTER_KEY=change-me-too \
  -v ./credentials-data:/data synara-credentials
```

Pre-built images: `ghcr.io/dertyp7214/synara-credentials:latest-dev`. A ready-made `docker-compose.credentials.yml` is included:

```bash
CREDENTIAL_SERVER_ADMIN_KEY=change-me CREDENTIAL_SERVER_MASTER_KEY=change-me-too \
  docker compose -f docker-compose.credentials.yml up -d
```

### Environment variables

| Variable                              | Default                       | Description                                                               |
|---------------------------------------|-------------------------------|---------------------------------------------------------------------------|
| `CREDENTIAL_SERVER_PORT`              | `8083`                        | HTTP port.                                                                |
| `CREDENTIAL_SERVER_ADMIN_KEY`         | *(empty)*                     | Key for the admin routes, sent as `X-Admin-Key`. While it is empty the admin routes answer 503. |
| `CREDENTIAL_SERVER_MASTER_KEY`        | *(empty)*                     | Master key for the encryption at rest. When empty, a key file is used.    |
| `CREDENTIAL_SERVER_KEY_FILE`          | `master.key`                  | Path of the generated key file (`/data/master.key` in the Docker image).  |
| `CREDENTIAL_SERVER_ISSUER`            | `synara-credentials`          | Issuer claim of the issued tokens.                                        |
| `CREDENTIAL_SERVER_TOKEN_TTL_SECONDS` | `900`                         | Lifetime of the issued tokens.                                            |
| `CREDENTIAL_SERVER_DB_DRIVER`         | `org.sqlite.JDBC`             | JDBC driver. Use `org.postgresql.Driver` for PostgreSQL.                  |
| `CREDENTIAL_SERVER_DB_URL`            | `jdbc:sqlite:credentials.db`  | JDBC URL (`jdbc:sqlite:/data/credentials.db` in the Docker image).        |
| `CREDENTIAL_SERVER_DB_USER`           | *(empty)*                     | Database user (PostgreSQL only).                                          |
| `CREDENTIAL_SERVER_DB_PASSWORD`       | *(empty)*                     | Database password (PostgreSQL only).                                      |

## Bootstrapping with the CLI

The jar doubles as a command line tool that works directly on the database, so a fresh server can be set up without any HTTP call. Run it with the same environment as the server, especially the database and master key settings.

```bash
java -jar credential-server-all.jar credentials set-api-key acoustid.api <key>
java -jar credential-server-all.jar credentials set-oauth tidal.api --preset tidal.api --client-id <id> --client-secret <secret>
java -jar credential-server-all.jar credentials import-tiddl importer.tiddl --auth-file ~/.tiddl/auth.json
java -jar credential-server-all.jar clients create my-synara --grant acoustid.api --grant tidal.api --grant importer.tiddl:w
```

A credential has to exist before it can be granted. `clients create` prints the client id and the client secret once. A grant written as `<name>:w` also allows write-back.

The full command list:

```text
clients list
clients create <name> [--grant <credential>[:w]]...
clients rotate|enable|disable|delete|revoke-tokens <clientId>
clients grant <clientId> <credential> [--write-back]
clients ungrant <clientId> <credential>
credentials list
credentials set-api-key <name> <key>
credentials set-key-pair <name> <key> <secret>
credentials set-oauth <name> --preset <preset> --client-id <id> --client-secret <secret>
credentials set-apple <name> --team-id <id> --key-id <id> --p8 <path>
credentials import-file <name> --role <role> --file <path> [--role <role> --file <path>]...
credentials import-tiddl <name> --auth-file <path> [--client-id <id> --client-secret <secret>]
credentials tidal-login <name> --format tiddl|tdn [--client-id <id> --client-secret <secret>]
credentials test|delete <name>
keys rotate
```

`clients rotate` prints the new secret once. `credentials tidal-login` prints the verification link and code and waits until the login completes, fails, expires or is cancelled. For `importer.tiddl` and `importer.tdn` the `--client-id` and `--client-secret` options of `import-tiddl` and `tidal-login` are optional. Without them the stored client is kept, and a new credential uses the public default client of tiddl and tidal-dl-ng. Other Tidal session names need both options on their first save. The `set-*` and `import-*` commands also take `--description <text>`. `set-oauth` accepts `--token-url`, `--auth-style basic|form` and `--scope` instead of or on top of a preset. Run `java -jar credential-server-all.jar help` to print the list. Without a command the jar starts the server.

The same management is available over the admin REST routes under `/admin`, protected by `X-Admin-Key`.

## Connecting a Synara server

Admins connect a server in the Credentials settings page of their Synara client. The page lists the credentials stored on the Synara server itself first and the credential server connection below. When the configured admin key is accepted by the credential server, the page also registers the server as a new client, stores the generated secret directly without showing it, and lets admins manage clients, grants and credentials of the credential server. The Tidal login runs there as a live device login.

Alternatively, set the connection through environment variables on the Synara server:

| Variable                          | Description                                              |
|-----------------------------------|----------------------------------------------------------|
| `CREDENTIAL_SERVER_URL`           | Base URL of the credential server.                       |
| `CREDENTIAL_SERVER_CLIENT_ID`     | Client id of this server.                                |
| `CREDENTIAL_SERVER_CLIENT_SECRET` | Client secret of this server.                            |
| `CREDENTIAL_SERVER_ADMIN_KEY`     | Optional. Lets the settings page manage the credential server. |

Values saved on the settings page take precedence over the environment.

### Fallback

Only names that the client was granted are fetched remotely. Every other name keeps using the local configuration of the Synara server, so a setup can be migrated one credential at a time. A name that is granted but can not be fetched because the credential server is unreachable is reported as unavailable in the log. It does not silently fall back to the local value. Which names are granted is read when the token is exchanged and can therefore be up to one token lifetime out of date.

Importers whose login is managed by the credential server report that instead of starting a local login.

## HTTP API

| Method | Path                         | Auth   | Description                                                        |
|--------|------------------------------|--------|--------------------------------------------------------------------|
| `GET`  | `/health`                    | no     | Liveness probe.                                                    |
| `POST` | `/token`                     | no     | Exchanges a client id and secret for a JWT with the granted names. |
| `GET`  | `/credentials`               | bearer | Lists the granted names with their kind and write-back flag.       |
| `GET`  | `/credentials/{name}`        | bearer | Resolves one credential. Answers 403 if it is not granted.         |
| `PUT`  | `/credentials/{name}/files`  | bearer | Writes changed importer files back. Needs the write-back grant and answers 409 on a fingerprint mismatch. |
| any    | `/admin/...`                 | admin  | Clients, grants, credentials, presets, Tidal login and signing key rotation. |

The wire models live in the `common-credentials` module (`dev.dertyp.credentials`).

## Security notes

- Put the server behind a reverse proxy with TLS. Credentials and tokens travel over the network, and the Synara server warns when it is pointed at a plain http URL on a non-local host.
- Keep the master key away from the data volume when possible. Prefer `CREDENTIAL_SERVER_MASTER_KEY` from a secret store over the generated key file, otherwise a copy of the volume contains both the encrypted data and the key.
- The admin key protects everything under `/admin`. Use a long random value and do not expose the admin routes publicly if you do not need to.
- Give every Synara server its own client with only the grants it needs. Revoking a client takes effect immediately.
- Importer files stay on the consumer's disk between runs, so the consumer's disk has to be trusted as much as the server itself.
- If several Synara servers share one Tidal session, a refresh token rotation by one of them can invalidate the token of another. The write-back check detects this, but the losing server may need a new login.
