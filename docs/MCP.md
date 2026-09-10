# Synara MCP (listen history)

Synara exposes a read-only [Model Context Protocol](https://modelcontextprotocol.io) server so an AI assistant can query a user's listening history in detail: raw listens, top lists, timelines, summaries and the dashboard statistics. Nothing exposed over MCP can modify data.

## Endpoint

| Item      | Value                                                                 |
|-----------|-----------------------------------------------------------------------|
| URL       | `POST /mcp` on the Synara server                                      |
| Transport | Streamable HTTP, stateless (every request is authenticated on its own) |
| Body      | JSON-RPC 2.0 (`Content-Type: application/json`)                       |
| Accept    | `application/json, text/event-stream`                                 |

`GET` and `DELETE` on `/mcp` answer `405`; there is no long-lived session and no `Mcp-Session-Id` header.

## Authentication

Create an API key with the `mcp` scope (through the API key settings of any client, or `IApiKeyService.createApiKey(label, ["mcp"])`). Send it with every request as one of

- `Authorization: Bearer <key>`
- `X-API-Key: <key>`
- `?apiKey=<key>`

Requests without a valid key that carries the `mcp` scope get `401`. All tools only ever return data belonging to the key's user.

### Claude Code

```bash
claude mcp add --transport http synara https://your-server/mcp --header "Authorization: Bearer synara_..."
```

### Claude Desktop / other clients

Add a remote MCP server with the URL `https://your-server/mcp` and a custom `Authorization: Bearer synara_...` header.

## Conventions

- Time inputs accept ISO-8601 (`2024-03-01`, `2024-03-01T12:00:00Z`, `2024-03-01T12:00` interpreted in `timezone`) or epoch milliseconds.
- Time outputs are objects `{ "epochMs": 1709294400000, "iso": "2024-03-01T13:00:00+01:00" }`.
- `timezone` is an IANA zone name (`Europe/Berlin`) and defaults to UTC. It controls day, week and month boundaries and the ISO output offset.
- `from` is inclusive, `to` is exclusive; both are optional (open-ended).
- Ids are the library's song, artist and album UUIDs. Use `search_library` to resolve names to ids.
- Listens imported from ListenBrainz that could not be matched to a library song are still included. They carry `unmatched` metadata (track, artist and release names plus MusicBrainz ids) instead of `song`, and appear in top lists with `matched: false` and no `id`.
- Every tool is annotated `readOnlyHint = true` and `idempotentHint = true`.

## Tools

### `search_library`

Resolve names to library ids.

| Parameter | Type                                   | Default | Notes                 |
|-----------|----------------------------------------|---------|-----------------------|
| `query`   | string (required)                      |         | Free-text search      |
| `types`   | array of `songs`, `artists`, `albums`  | all     |                       |
| `limit`   | integer 1..50                          | 10      | Per type              |

Returns `{ songs: [...], artists: [{ id, name }], albums: [{ id, name }] }`. Songs contain `id`, `title`, `artists`, `album`, `durationMs`, `releaseDate`, `genres`, `isrc`, `recordingMbid`, `explicit`.

### `get_listens`

Raw listens, newest first, keyset-paginated.

| Parameter       | Type                           | Default |
|-----------------|--------------------------------|---------|
| `from`, `to`    | time                           | open    |
| `limit`         | integer 1..1000                | 100     |
| `cursor`        | string from a previous page    |         |
| `songId`, `artistId`, `albumId` | uuid           |         |
| `source`        | `LOCAL` or `LISTENBRAINZ`      | both    |
| `qualifiedOnly` | boolean                        | false   |
| `timezone`      | IANA zone                      | UTC     |

Returns `{ listens: [...], nextCursor, hasMore }`. Each listen has `id`, `listenedAt`, `msPlayed` (raw client report, may be null), `playedMs` (effective played duration), `qualified` (counts as a full play: at least three minutes or half the song), `source`, and either `song` or `unmatched`.

### `get_listening_summary`

Totals for a window, optionally scoped to one song, artist or album.

| Parameter    | Type      | Default |
|--------------|-----------|---------|
| `from`, `to` | time      | open    |
| `songId`, `artistId`, `albumId` | uuid |  |
| `timezone`   | IANA zone | UTC     |

Returns `listenCount`, `listenedMs`, `uniqueSongs`, `uniqueArtists`, `uniqueAlbums`, `firstListen`, `lastListen`, `hourOfDay` (24 counts, local time), `dayOfWeek` (7 counts, Monday first) and `daysWithListens`.

### `get_top`

Ranked songs, artists or albums for a window.

| Parameter    | Type                                | Default       |
|--------------|-------------------------------------|---------------|
| `kind`       | `songs`, `artists`, `albums` (required) |           |
| `from`, `to` | time                                | open          |
| `limit`      | integer 1..200                      | 20            |
| `offset`     | integer                             | 0             |
| `orderBy`    | `listenCount` or `listenedMs`       | `listenCount` |
| `artistId`, `albumId` | uuid (scope the ranking)   |               |
| `timezone`   | IANA zone                           | UTC           |

Returns `{ kind, orderBy, offset, limit, total, entries }` where each entry has `rank`, `matched`, `id`, `name`, `artistName`, `albumName`, `mbid`, `listenCount`, `listenedMs`, `firstListen`, `lastListen`.

### `get_listen_timeline`

Listen counts per bucket.

| Parameter    | Type                                            | Default |
|--------------|-------------------------------------------------|---------|
| `bucket`     | `hour`, `day`, `week`, `month`, `year` (required) |       |
| `from`, `to` | time                                            | open    |
| `songId`, `artistId`, `albumId` | uuid                         |         |
| `timezone`   | IANA zone                                       | UTC     |

Buckets are aligned to local boundaries in `timezone` (weeks start on Monday) and empty buckets inside the range are included. A request that would produce more than 2000 buckets returns an error; narrow the window or use a coarser bucket.

### `get_listening_stats`

The same aggregate the clients' statistics page shows: listen count with previous-period comparison, top songs, artists and albums, listen clock, streaks and new discoveries.

| Parameter  | Type                                                                          | Default        |
|------------|-------------------------------------------------------------------------------|----------------|
| `range`    | `DAY`, `WEEK`, `LAST_WEEK`, `MONTH`, `LAST_MONTH`, `YEAR`, `LAST_YEAR`, `ALL_TIME` (required) | |
| `timezone` | IANA zone                                                                     | UTC            |
| `topLimit` | integer 1..100                                                                | 10             |
| `topOrder` | `LISTEN_COUNT` or `LISTENED_MS`                                               | `LISTEN_COUNT` |

For arbitrary windows combine `get_listening_summary`, `get_top` and `get_listen_timeline`.

### `get_now_playing`

No parameters. Returns the song the user is currently playing with `startedAt`, `positionMs` and `playing`, or `null`.

## Example

```bash
curl -s https://your-server/mcp \
  -H "Authorization: Bearer synara_..." \
  -H "Accept: application/json, text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_top","arguments":{"kind":"artists","from":"2024-01-01","to":"2025-01-01","limit":5,"timezone":"Europe/Berlin"}}}'
```
