# Synara Listen Backup

The listen-backup receiver is a small standalone service that stores a copy of a Synara server's listening history (server-side scrobbles) in its own database. The Synara server pushes listens to it incrementally through the `Listen Backup` background task, so a lost or corrupted main database does not take the listening stats with it.

Only listens recorded by the server itself (`source = LOCAL`) are backed up; ListenBrainz-imported listens can always be re-imported from ListenBrainz.

## How it works

1. Every listen row on the server carries an `updatedAt` timestamp that is bumped on insert and on every later change (for example when an unmatched listen is linked to a song).
2. The `Listen Backup` task selects all local listens with `updatedAt` newer than the stored cursor, sends them in batches to the receiver, and advances the cursor after each acknowledged batch.
3. The receiver upserts by listen id, so re-sending the same rows is harmless and changed rows overwrite their previous copy.
4. `resetCursor` on the server forces a complete re-push on the next run.

## Running the receiver

### Using Gradle

```bash
./gradlew :listen-backup:run
```

The receiver listens on port `8082` by default and stores listens in `listens.db` (SQLite) in the working directory.

### Using Docker

```bash
docker build -t synara-listen-backup -f Dockerfile.listen-backup .
docker run -p 8082:8082 -e BACKUP_KEY=change-me -v ./listen-backup-data:/data synara-listen-backup
```

Pre-built images: `ghcr.io/dertyp7214/synara-listen-backup:latest-dev`. A ready-made `docker-compose.listen-backup.yml` is included:

```bash
BACKUP_KEY=change-me docker compose -f docker-compose.listen-backup.yml up -d
```

### Environment variables

| Variable      | Default                       | Description                                                       |
|---------------|-------------------------------|-------------------------------------------------------------------|
| `PORT`        | `8082`                        | HTTP port.                                                        |
| `BACKUP_KEY`  | *(empty)*                     | Shared secret; requests must send it as `X-Backup-Key`. Empty disables the check (not recommended). |
| `DB_DRIVER`   | `org.sqlite.JDBC`             | JDBC driver. Use `org.postgresql.Driver` for PostgreSQL.          |
| `DB_URL`      | `jdbc:sqlite:listens.db`      | JDBC URL (`jdbc:sqlite:/data/listens.db` in the Docker image).    |
| `DB_USER`     | *(empty)*                     | Database user (PostgreSQL only).                                  |
| `DB_PASSWORD` | *(empty)*                     | Database password (PostgreSQL only).                              |

## HTTP API

| Method | Path                  | Auth | Description                                                          |
|--------|-----------------------|------|----------------------------------------------------------------------|
| `GET`  | `/health`             | no   | `{ ok, listenCount }`                                                |
| `GET`  | `/status?serverId=`   | yes  | `{ serverId, listenCount, lastReceivedAt, maxUpdatedAt }` for a server (or all servers when omitted). |
| `POST` | `/listens`            | yes  | Body `{ serverId, listens: [...] }`; upserts every listen by id and returns `{ received }`. |

The wire models live in the `common-listen-backup` module (`dev.dertyp.listenbackup`).

## Configuring the Synara server

Backup settings are managed by admins through `IListenBackupService` (RPC and REST):

- `updateConfig(ListenBackupConfig(enabled, url, key, batchSize))` — store the receiver URL and key. Passing `key = null` keeps the previously stored key.
- `testConnection(config?)` — probe the receiver and report how many listens it already holds for this server.
- `syncNow()` — run a push immediately.
- `resetCursor()` — re-push everything on the next run.
- `getState()` / `getStateFlow()` — configuration (without the key), server id, last sync time, last error and the number of pending listens.

The `Listen Backup` scheduled task runs every hour at minute 30 by default and does nothing until the backup is enabled and a URL is configured. Its schedule can be changed like any other task through `IScheduledTaskConfigurationService`.
