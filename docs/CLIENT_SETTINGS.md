# Client Settings

Synara can store client settings on the server instead of only on the device, so a client can restore its settings after a reinstall and, for the settings a user wants to follow them, share them across every device they own. The server never interprets a setting: a key is an opaque string the client chooses, and its value is an arbitrary JSON document carried as text.

See [docs/RPC_SERVICES.md](RPC_SERVICES.md) for the exact method signatures of `IClientSettingsService`; this page only covers the concepts and workflow.

## Scopes

Every entry lives in one of two scopes, chosen per key by the client:

- **SYNCED** — shared by all devices of the user.
- **DEVICE** — belongs to a single device, addressed by its `deviceId`.

A client merges the two scopes locally: read both, then let a DEVICE entry override a SYNCED entry of the same key. `getSnapshot` returns exactly that shape — the live entries of both scopes for one device in a single call.

## Device identity

`deviceId` is a stable string the client generates once and persists locally (a login session is the wrong identity here, since it changes on every login). It is:

- registered implicitly the first time a client reads or writes the DEVICE scope, or calls `getSnapshot`;
- named explicitly with `registerDevice(deviceId, name, platform)`, which is optional and only makes the device list human-readable;
- a namespace within the user's own account, not a security boundary — any authenticated request for that user can address any `deviceId` the user owns.

## Versions, conflicts and retries

Each scope has its own monotonically increasing `version`, incremented by one on every accepted write. Every entry remembers the version it was last written at.

A write (`setSettings`) carries a `baseVersion` per key: the version the client last saw for that key, or `0` if the client believes the key does not exist. The write is a batch, and it is all-or-nothing:

- if every key's `baseVersion` still matches the server (or the key is genuinely new/tombstoned with `baseVersion = 0`), the whole batch is applied and the scope advances by one version;
- if any key has moved past the client's `baseVersion`, nothing is written and the result is `Conflict`, carrying every clashing key together with the entry currently stored on the server.

Worked example:

1. Device A reads key `theme`, which does not exist yet (`baseVersion = 0`), and writes `theme = "dark"`. The scope moves from version 0 to version 1; the entry is now at version 1.
2. Device B, still unaware of A's write, writes `theme = "light"` with `baseVersion = 0`. Its `baseVersion` no longer matches the stored version (1), so the write comes back as `Conflict(version = 1, conflicts = [{ key: "theme", baseVersion: 0, current: { value: "dark", version: 1, ... } }])`. Nothing was written.
3. Device B merges the conflict (keep its own value, keep the server's, or combine both) and retries `setSettings` with `baseVersion = 1` for `theme` — or repeats the same write with `force = true` to overwrite the server unconditionally, skipping the check.

## Deletes and tombstones

Writing a key with a `null` value deletes it. The key is not removed outright; it becomes a tombstone (`deleted = true`) so other devices that sync incrementally learn about the deletion instead of silently missing the key. Deleting a key that is already absent or already a tombstone is a no-op and does not bump the version. `getSettings` hides tombstones unless called with `includeDeleted = true`.

## Incremental sync

`getChanges(scope, sinceVersion, device, limit)` returns the entries written after `sinceVersion`, tombstones included, ordered by the version they were written at. If more entries exist than `limit` allows, `hasMore` is `true` and the client asks again with the version of the last entry it received.

Tombstones are purged after 30 days (see Retention below). If a client asks for changes since a version older than the oldest tombstone still kept, the response also sets `fullResync = true`: some deletions may already be gone, so the client must instead re-read the whole scope (`getSettings` or `getSnapshot`) rather than trust the partial change list.

## Live updates

`observeSettings()` is a stream (SSE over REST, a `Flow` over RPC) of `ClientSettingsChange` events: every accepted write — from any device — emits the scope, the version it advanced to, the keys it touched and the device that wrote them, so other devices know what to pull without polling.

## History and restore

The server keeps the last 20 superseded values per key. `getHistory(scope, key, device, limit)` lists them newest first. `restore(scope, key, version, device, force)` writes a historic value back as a new version through the normal write path (so it goes through the same conflict check, and the value it replaces itself moves into history); restoring a version that has already aged out of history fails.

## Limits

| Limit | Value |
|---|---|
| Key length | ≤ 255 characters |
| Value size | ≤ 64 KiB (UTF-8) |
| Entries per scope | ≤ 2000 |
| Entries per write | ≤ 200 |
| Device id length | ≤ 64 characters |

## Retention

- Tombstones older than 30 days are purged (this is what can trigger `fullResync`).
- Devices not seen for 180 days are dropped together with their DEVICE-scoped settings and history; the SYNCED scope is never touched by this.
- Both run daily in the `client-settings-cleanup` background task.

## REST

`IClientSettingsService` is also exposed as REST under `/clientSettings`. Reads (`getSettings`, `getSnapshot`, `getChanges`, `getHistory`, `getDevices`) are `GET`; `setSettings` is a `PUT` with the entries in the request body; `observeSettings` is a Server-Sent Events stream. All routes require authentication.
