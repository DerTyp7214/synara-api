# Streaming and Playback

This page is for anyone implementing playback against Synara — a web player, a mobile or desktop app, a car head unit, a script that feeds a hardware player. It covers where audio bytes come from and in which formats, how to seek, how covers are served, and the three protocols a good client speaks while it plays: playback reporting (so the server knows what is running and can scrobble it), playback state and sessions (so devices see each other), and the versioned queue (so devices share one list). The transport rules behind all of it — verbs, headers, errors, SSE — are in [CLIENT_REST.md](CLIENT_REST.md); the exact signatures are in [REST_API.md](REST_API.md#devdertypservicesisongservice) and [RPC_SERVICES.md](RPC_SERVICES.md#devdertypservicesisongservice).

## Audio sources

A song has up to three playable representations. Sizes are bytes, `quality` is kbps.

| Source | Route | Exists when | Served as |
|---|---|---|---|
| Original file | `GET /song/streamSong/{id}` | always | the stored file, unchanged: FLAC, WAV, AIFF, MP3, M4A/MP4 or OGG |
| Transcoded copy | `GET /song/downloadSong/{id}?quality=<kbps>&format=OPUS\|AAC` | on demand, produced and cached on first request | `OPUS` → Opus in Ogg, `AAC` → AAC in MP4 |
| Dolby Atmos | `GET /song/streamSongAtmos/{id}` | the song has an Atmos variant **and** the client's `X-Api-Version` supports `DOLBY_ATMOS` (see [API_CONSTANTS.md#features](API_CONSTANTS.md#features)) | E-AC-3 JOC in an MP4 container |

Notes that matter in practice:

- **The original is not transcoded** — whatever the importer stored is what you get. `downloadSong` with `quality` ≤ 0 also returns the original.
- **Transcoding happens during your request.** The first `downloadSong` for a given (file, kbps, format) runs ffmpeg to completion before the first byte arrives; afterwards the result is cached on disk and reused. `force` defaults to `true`, which compares the cached copy's duration with the source and re-transcodes on a mismatch; `force=false` returns a cached copy immediately and is the right choice for a player.
- **WAV and AIFF are converted to FLAC** for clients that don't support `LOSSLESS_WAV_AIFF` (see [API_CONSTANTS.md#features](API_CONSTANTS.md#features)), once, cached, and served in place of the original. Clients that support it receive the real file.
- **Atmos is gated twice.** Without `DOLBY_ATMOS` support the `atmos` field is stripped from songs and `streamSongAtmos` answers `404`. Offer the Atmos stream only on platforms that actually decode E-AC-3 JOC; clients do not ship their own decoders, and a variant that cannot be decoded is simply not offered.
- Everything else in the library that is not a song follows its own route: podcasts below, radio below, and never through `streamSong`.

### What the song tells you

[`Song`/`UserSong`](MODELS.md#devdertypdatausersong) carry, once the client's `X-Api-Version` supports `AUDIO_INFO` (see [API_CONSTANTS.md#features](API_CONSTANTS.md#features)), an `audio` object and — when one exists — an `atmos` object of the same shape ([`AudioInfo`](MODELS.md#devdertypdataaudioinfo)):

| Field | Meaning |
|---|---|
| `codec` | file codec, e.g. `flac`, `wav`, `eac3` |
| `sampleRate` | Hz |
| `bitsPerSample` | 0 for lossy codecs |
| `bitRate` | kbit/s |
| `fileSize` | bytes |
| `channels` | 2 for stereo, 6 for 5.1 |

Use them to decide *before* fetching: pick Atmos when `atmos != null` and your platform decodes it, the original when the connection allows `audio.fileSize`, otherwise a `downloadSong` quality. Without `AUDIO_INFO` support the same numbers arrive in the flat, deprecated `sampleRate`/`bitsPerSample`/`bitRate`/`fileSize` fields instead.

`duration` is milliseconds. `audioStartMs` is the offset of the first audible sound (or `null` when the song has not been analysed) — useful to skip leading silence. `coverId`, `blurHash`, `animatedCoverId` and `animatedCoverImageId` are covered under *Images* below.

### Sizes and seeking

| Route | Returns |
|---|---|
| `GET /song/streamSize/{id}` | size of the original stream |
| `GET /song/atmosStreamSize/{id}` | size of the Atmos variant, `0` when there is none |
| `GET /song/downloadSize/{id}?quality=&force=&format=` | size of the transcoded copy — **this transcodes if the copy does not exist yet**, so call it with `force=false` or accept the wait |

All three return a bare JSON number. `HEAD` on the stream route itself is usually the better option: it answers with `Content-Length` and the content type the server resolved for the file, and costs nothing.

**Only `HEAD` reports that type.** The `GET` body — ranged or not — is typed by Ktor from the file name, so a FLAC song arrives as `application/octet-stream` even though `HEAD` says `audio/flac`. Podcast episodes are no different: the server derives `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/ogg`, `audio/flac` or `audio/wav` from the extension, but that value reaches the `HEAD` response only. Decide what to decode from `HEAD` or from the song's `audio.codec`, not from the streamed response.

All three audio routes are file responses: `Accept-Ranges: bytes`, `Content-Disposition: inline; filename="…"`, and Ktor's `PartialContent` handling for `Range` (at most 10 ranges per request), which yields `206` with `Content-Range`. Normal players seek with `Range` and need nothing else.

The `offset` (bytes) and `chunkSize` query parameters exist for the fallback path — when the server has no file to hand out but can still produce a byte stream, it streams from `offset` without range support. That is the path a podcast relay takes; for songs, prefer `Range`.

## Podcasts

Podcasts are deliberately not songs: an episode is never scrobbled, never appears in music search, and is always played through one route.

| Route | Purpose |
|---|---|
| `GET /podcast/streamEpisode/{episodeId}` | the only way to play an episode; `GET` and `HEAD` |
| `GET /podcast/streamSize/{episodeId}` | size in bytes, `0` when it cannot be determined |
| `POST /podcast/reportPlayback` | store the listening position |
| `PUT /podcast/played/{episodeId}?played=` | mark listened / unlistened |
| `GET /podcast/observeProgress` | SSE of [`PodcastEpisodeProgress`](MODELS.md#devdertypdatapodcastepisodeprogress) for this user |
| `GET /podcast/inProgress`, `GET /podcast/lastPlayed` | resume points for the UI |

`streamEpisode` behaves differently depending on where the audio lives, and the client does not need to know which: when the server stored the episode (an imported feed episode, or one of the local podcast library) it is a normal file response with `Range` support, and `HEAD` reports the `audio/*` type the server derived from the extension; when the server only relays the origin, the response is a plain byte stream without `Accept-Ranges`, and `offset` is the only way to resume mid-file. Write your player so that a missing `Accept-Ranges` degrades to sequential playback.

Progress reporting follows the same rhythm as songs — on play, on pause, after a seek, and every 10 to 15 seconds — with [`EpisodePlaybackReport`](MODELS.md#devdertypdataepisodeplaybackreport) as the body (`episodeId`, `positionMs`, optional `durationMs`, `completed`, `deviceId`). The last report wins. The server marks an episode completed on its own once less than 30 seconds or less than 5 percent remain, so a client does not have to guess the threshold. Other devices of the same user follow along through `observeProgress`.

## Radio

Two different things are called radio.

**Server-mixed stream.** `/radio/**` produces an endless `audio/aac` ADTS stream, transcoded song by song at the requested bitrate, for players that just want a URL.

| Route | Seeded by |
|---|---|
| `GET /radio/stream?quality=<kbps>&type=&songId=&playlistId=&albumId=&artistId=` | a new session; `type` is `RANDOM` (default), `LAST_WEEK`, `LAST_MONTH`, `LAST_YEAR`, and `songId` may repeat |
| `GET /radio/{sessionId}/stream?quality=<kbps>` | an existing session, continued where it stopped |
| `GET /radio/channel/{channelId}/stream?quality=<kbps>` | a curated channel |

These routes authenticate **only with an API key** — `?apiKey=`, `X-API-Key`, or `Authorization: Bearer <key>` — created with `POST /apiKey/apiKey?label=…&scopes=radio`. A JWT is not accepted here. `quality` is required. Send `Icy-MetaData: 1` to receive ICY metadata: the response then carries `icy-name`, `icy-br` and `icy-metaint: 16000`, and a `StreamTitle='Artist - Title';` block is interleaved every 16000 bytes.

**Client-mixed station.** `POST /radio/radioSession?type=` (optional [`RadioSeed`](MODELS.md#devdertypdataradioseed) body) returns a session id, and `GET /radio/radioFlow/{sessionId}` is an SSE stream of song ids that never repeats within the session. A normal JWT client uses this and plays each id through `streamSong`, keeping gapless buffering, scrobbling and the queue under its own control.

## Images

All three image routes are public — no token — which is exactly what `<img>` tags and cover caches need.

| Route | Serves |
|---|---|
| `GET /image/imageData/{id}?size=` | a still cover or avatar |
| `GET /animatedImage/imageData/{id}` | an animated cover (a short looping video) |
| `GET /release/releaseImage/{releaseId}?size=` | the cover of a release-feed entry |

`size=0` (the default) returns the original bytes. `size=N` returns a thumbnail fitted into `N`×`N`, re-encoded as PNG for PNG sources and JPEG otherwise; every (id, size) pair is cached server-side, so ask for the size you will display instead of downscaling in the client. Stored bytes that are not an image are answered with `404` rather than served, so a broken cover never poisons your image cache.

`releaseImage` behaves the same when the cover is stored locally. When it is not, the server proxies the Cover Art Archive and uses `size` to pick the archive's variant — `front-250` up to 250, `front-500` up to 500, `front-1200` up to 1200, the full `front` above that — caching the result, and remembering a miss for an hour so repeated misses stay cheap (`404`).

Content types are sniffed from the magic bytes (PNG, JPEG, GIF, WebP, BMP, AVIF, HEIC, MP4, QuickTime, WebM), which is also how an animated cover ends up as `video/mp4` or `video/webm`; anything unrecognised is sent as `application/octet-stream`.

Use the **blur hash** as the placeholder while the image loads: songs carry `blurHash` next to `coverId` and `animatedCoverBlurHash` next to `animatedCoverId`, and [`Image`](MODELS.md#devdertypdataimage) additionally exposes `width`, `height`, `primaryColor`, `luminance` and a five-colour `palette` for theming. An animated cover also has a still first frame (`animatedCoverImageId`) — show that until the video is buffered, and never make playback wait for it.

Image ids are stable for the lifetime of the image, so cache by id.

## Reporting playback

Reporting is what makes now-playing, listening statistics and ListenBrainz work. It is cheap, and the server needs it whether or not you also use the queue.

1. **On track start** — `POST /scrobble/nowPlaying/{songId}`. The now-playing state expires on its own after the song's duration, so a client that dies does not leave a ghost.
2. **On every playback event and while playing** — `POST /scrobble/reportPlayback` with [`PlaybackReport`](MODELS.md#devdertypdataplaybackreport): `{"songId": …, "positionMs": 12345, "playing": true, "sentAt": <client epoch ms>}`. Send it on play, pause, resume and seek, and every 10 to 15 seconds while playing. The response is the server's epoch milliseconds at receipt, so you can measure the offset between client and server clocks; `sentAt` lets the server compensate transport delay.
3. **On finish** — `POST /scrobble/listened` with [`ScrobbleRequest`](MODELS.md#devdertypdatascrobblerequest): `{"songId": …, "listenedAt": <epoch ms>, "msPlayed": …}`. `listenedAt` defaults to the server's current time.
4. **On stop** — `POST /scrobble/clearNowPlaying`.
5. **To follow along** — `GET /scrobble/recentListensFlow?limit=20` is an SSE stream of [`RecentListens`](MODELS.md#devdertypdatarecentlistens) (`nowPlaying` plus the recent list), debounced by 100 ms; `limit` is clamped to 1…1000.

All five take a JWT; none of them accept an episode id — podcasts have their own reporting, described above.

## Sessions and playback state

Every login creates a **session**, and its id is the `ses` claim of the JWT. That is where a client gets its own session id from: decode the token you received from `/authenticate` and read `ses`. `GET /session/sessions` lists the user's sessions (`id`, `userAgent`, `ipAddress`, `lastActive`, `isActive`) and `DELETE /session/deactivateSession/{sessionId}` ends one; `GET /queue/syncDevices` marks the calling session with `isCurrent`, which is the easiest way to recognise yourself in a device list without touching the token.

Playback state is stored **per session**, so devices can watch each other:

| Route | Purpose |
|---|---|
| `GET /playback/playbackState/{sessionId}` | read one device's [`PlaybackState`](MODELS.md#devdertypdataplaybackstate) |
| `PUT /playback/playbackState/{sessionId}` | write your own (body: the state object) |
| `GET /playback/observePlaybackState/{sessionId}` | SSE of that device's state |

`PlaybackState` carries its own `queue`, `currentIndex`, `isPlaying`, `positionMs`, `shuffleMode`, `repeatMode` and `sourceId`. It is a snapshot used to transfer a queue to another device — write your own state so that device can pick it up — not a way to watch or steer one live; live control of another device goes through the remote control channel described below. The shared, paged, conflict-checked list further down is the queue a client should keep in sync.

## Online devices and capabilities

A client that wants to be discoverable by the user's other devices — for queue sync, remote control, or whatever comes next — describes itself when it opens the request channel: `connect(description)` over RPC, or `GET /clientRequest/connect?description=<json>` as SSE. The [`ClientDescription`](MODELS.md#devdertypdataclientdescription) carries `deviceName`, an optional `platform` and `deviceId` (the settings-sync device id, for correlation only) and a set of [`ClientCapability`](MODELS.md#devdertypdataclientcapability): `QUEUE_SYNC`, `REMOTE_CONTROL`, `REMOTE_VOLUME`.

Presence lasts exactly as long as that stream: a session appears in `getOnlineDevices` for as long as its `connect` request is open, and disappears the moment the connection closes. There is no announce call and nothing to prune. Changing capabilities means resubscribing with a new description — the newer one wins.

`observeRequests` is still there as the anonymous form of the same stream: a client that never describes itself stays reachable for requests such as `UploadQueue`, but is not listed as an online device. `GET /queue/syncDevices` is unrelated and unaffected — it stays the persisted list of queue-sync participants, independent of who is connected right now.

| Route | Purpose |
|---|---|
| `GET /clientRequest/connect?description=` (SSE) | subscribe to the request channel and be listed as online with this description |
| `GET /clientRequest/onlineDevices` | the user's currently connected sessions, newest first |

## Remote control

A device that connected with `REMOTE_CONTROL` (and `REMOTE_VOLUME` if it can act on volume) receives `ControlPlayback` requests on its request stream, each carrying a [`PlaybackCommand`](MODELS.md#devdertypdataplaybackcommand): `Play`, `Pause`, `TogglePlayPause`, `Next`, `Previous`, `SeekTo{positionMs}`, `SetShuffle{enabled}`, `SetRepeat{mode}` or `SetVolume{volume}` (`0..1`). The device executes it, reports its resulting state with `POST /remoteControl/reportStatus`, and acknowledges the request the same way as any other: `POST /clientRequest/complete/{requestId}?status=COMPLETED` (or `REJECTED`).

A controller picks a device from `getOnlineDevices`, then sends `POST /remoteControl/sendCommand/{sessionId}` with the command as the JSON body — sealed, so it carries a `type` discriminator, e.g. `{"type":"SeekTo","positionMs":30000}` — and gets back a `ClientRequestStatus`: `COMPLETED`, `REJECTED`, `TIMED_OUT` after 10 seconds, or `UNREACHABLE`. To read the target's current state instead of commanding it, `GET /remoteControl/status/{sessionId}` returns the last report and `GET /remoteControl/observeStatus/{sessionId}` follows it as SSE, replaying the last status immediately on subscribe.

[`RemotePlaybackStatus`](MODELS.md#devdertypdataremoteplaybackstatus) carries `songId`, `isPlaying`, `positionMs`, `durationMs`, `shuffleMode`, `repeatMode`, `volume` and `reportedAt` (server epoch ms, stamped on receipt). A controlled device reports on every change and, while playing, every 10 to 15 seconds — the same rhythm as playback reporting above — and a controller should project the position forward instead of waiting for the next report: `positionMs + (now - reportedAt)` while playing.

| Route | Purpose |
|---|---|
| `POST /remoteControl/reportStatus` | the controlled device publishes its status |
| `GET /remoteControl/status/{sessionId}` | last reported status of one of the user's online devices |
| `GET /remoteControl/observeStatus/{sessionId}` (SSE) | follow that status, replaying the last one immediately |
| `POST /remoteControl/sendCommand/{sessionId}` | deliver a `PlaybackCommand` and wait for the ack |

A session belonging to another user answers `403`. A target that is not online, lacks `REMOTE_CONTROL`, or a `SetVolume` outside `0..1` or sent to a device without `REMOTE_VOLUME`, all answer `400`. As with queue observation, presence and pending requests live in the memory of a single server instance — there is no cross-instance fan-out.

## The shared queue

`IQueueService` stores one queue per user, shared by all their devices, with a monotonically increasing `version` incremented by every accepted write. **Every write carries the `baseVersion` it was based on**; if the server has moved past it, the write is rejected and returns the current state instead of overwriting someone else's change.

Reading:

- `GET /queue/queueInfo` → [`QueueInfo`](MODELS.md#devdertypdataqueueinfo): `version`, `total`, `currentIndex`, `shuffleMode`, `repeatMode`, `sourceId`, and who wrote last (`modifiedBySessionId`, `modifiedByDeviceName`). A user without a stored queue reports version 0.
- `GET /queue/queue?page=&pageSize=&includeSongs=` → a page of [`QueueItem`](MODELS.md#devdertypdataqueueitem) in original order; each item also carries its `shuffledPosition`. Leave `includeSongs` false for large queues and resolve songs separately.
- `GET /queue/observeQueue` → SSE of `QueueInfo`, one emission after every successful write, including writes from other devices. There is **no initial snapshot**: a fresh subscription stays silent until someone writes, so read `queueInfo` (and the pages you need) once when you subscribe.

Incremental writes — all of them take `baseVersion` and an optional `force`, and return [`QueueWriteResult`](MODELS.md#devdertypdataqueuewriteresult), which is `{"type": "Ok", "info": …}` or `{"type": "Conflict", "info": …}`:

| Route | Change |
|---|---|
| `POST /queue/insert` | insert items at a position of the active order |
| `DELETE /queue/entries?baseVersion=&queueIds=&force=` | remove entries by `queueId`; unknown ids are ignored and the current index follows the playing entry |
| `POST /queue/move/{queueId}` | move one entry |
| `PUT /queue/currentIndex` | set the playing index (clamped) |
| `PUT /queue/modes` | set `shuffleMode` and `repeatMode` |

"Active order" means the shuffled order while shuffle is on and the original order otherwise. Enabling shuffle server-side makes the server build a shuffled order starting at the entry that is playing, so `currentIndex` becomes 0; disabling it puts the index back on that entry's original position. A player that shuffles by itself uploads its own order instead.

Replacing the whole queue is a three-step upload, because a queue can be large:

1. `POST /queue/beginUpload?baseVersion=&force=` → [`QueueUploadStart`](MODELS.md#devdertypdataqueueuploadstart), either `{"type": "Started", "uploadId": …, "expiresAt": …}` or `{"type": "Conflict", "info": …}`. Starting an upload discards any previous staged upload of that user, and a staged upload expires if it is not committed.
2. `POST /queue/uploadPage/{uploadId}` with the next page of items (body: the item list) → the number staged so far. Repeat.
3. `POST /queue/commitUpload/{uploadId}` with [`QueueMeta`](MODELS.md#devdertypdataqueuemeta) (`currentIndex`, `shuffleMode`, `repeatMode`, `sourceId`) → `QueueWriteResult`. Only now does anything become visible. Entries whose song no longer exists are dropped and both orders are renumbered.

`POST /queue/cancelUpload/{uploadId}` throws a staged upload away.

Handling a `Conflict` is the same decision every time: the result carries the server's current `QueueInfo`, so either re-read the queue and re-apply your change on top of it, or repeat the call with `force=true` when the user's intent clearly outranks the stored state (they pressed play on *this* device).

Device participation:

- `PUT /queue/syncEnabled?enabled=&deviceName=` opts the calling session in or out and names it.
- `POST /queue/ackSynced?version=` records that this device has pulled up to a version.
- `GET /queue/syncDevices` lists participating devices with `lastSyncedVersion`, `lastActive` and `isCurrent`.
- `POST /queue/requestUploadFrom/{sessionId}` asks another device for its live queue and returns `COMPLETED`, `REJECTED`, `TIMED_OUT` or `UNREACHABLE`.

The other side of that request arrives on the request channel as `{"type": "UploadQueue", "id": …, "requestedBySessionId": …}`. `connect` (see *Online devices and capabilities* above) is the preferred way to subscribe to it, since it also makes the device discoverable; `GET /clientRequest/observeRequests` (SSE) remains the anonymous subscription for clients that do not describe themselves. Either way, a client that supports it subscribes once and answers by uploading its queue with `force=true` and passing the request id to `commitUpload` — the requester explicitly wants *this* device's queue, so it is expected to win — or reports `POST /clientRequest/complete/{requestId}?status=REJECTED`.

## Platform notes

Media elements and `EventSource` cannot set an `Authorization` header, and the server has no signed URLs or token query parameter for the generated routes. There are exactly two ways around it: the `synara-auth` cookie on the same origin, or the public image routes.

- **Web.** Store the JWT in the cookie after login (`document.cookie = "synara-auth=" + token + "; path=/; SameSite=Strict"`) and point `<audio src="/song/streamSong/<id>">` at the same origin. CORS does not allow credentials, so a cross-origin deployment must be put behind a shared reverse proxy. `<img src="/image/imageData/<id>?size=512">` needs no cookie at all.
- **iOS / macOS (`AVPlayer`).** Build the asset with the cookie attached — `AVURLAsset(url:options: [AVURLAssetHTTPCookiesKey: [cookie]])` with an `HTTPCookie` for `synara-auth` — and let `AVPlayer` do its own ranged requests. Atmos: only offer `streamSongAtmos` where E-AC-3 JOC playback exists; otherwise fall back to `streamSong`.
- **Android (ExoPlayer).** The HTTP data source is configurable, so prefer the header: `DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token", "X-Api-Version" to "6"))`. Range handling comes for free.
- **Desktop.** Same rule as everywhere else: play what the platform decodes. Clients do not bundle native decoders, so an unsupported variant (Atmos in particular) is simply not streamed — check `atmos` on the song and hide the option.

## Checklist

1. Send `X-Api-Version` on every request, or you will receive version-1 shapes without `audio`, `atmos` or title tags.
2. Choose a source from `audio`/`atmos` before fetching; use `HEAD` or the size routes when you need the exact length.
3. Seek with `Range`; fall back to `offset` only when the response has no `Accept-Ranges`.
4. Warm a transcode with `downloadSize`/`downloadSong` (`force=false`) before you need it, and never block the UI on the first call.
5. Report playback: `nowPlaying` on start, `reportPlayback` on every event and every 10–15 s, `listened` on finish, `clearNowPlaying` on stop. Podcasts use `POST /podcast/reportPlayback` instead.
6. Show covers from the public image routes at the size you render, with the blur hash as the placeholder.
7. If you sync the queue: read `queueInfo` once, then subscribe to `observeQueue` (it stays silent until the first write), always send `baseVersion`, handle `Conflict` by re-reading or forcing deliberately, and answer `UploadQueue` requests.
8. Write your own `PlaybackState` per session if you want other devices to see and control you.
9. Connect with `connect(description)` (not the anonymous `observeRequests`) if you want to be listed as an online device, and resubscribe whenever your capabilities change.
10. If the user enabled remote control: handle `ControlPlayback` on your request stream, report your status on every change and every 10–15 s while playing, and always acknowledge with `complete`.

## Next

- [CLIENT_REST.md](CLIENT_REST.md) — transport rules, headers, errors, SSE.
- [REST_API.md](REST_API.md#devdertypservicesipodcastservice) — every route with its parameters.
- [RPC_SERVICES.md](RPC_SERVICES.md#devdertypservicesiqueueservice) — the same methods as typed RPC.
- [AUTHENTICATION.md](AUTHENTICATION.md) — tokens, sessions and API keys.
- [API_VERSIONING.md](API_VERSIONING.md) — what each version changes about audio and songs.
- [CLIENT_GETTING_STARTED.md](CLIENT_GETTING_STARTED.md) — first steps against a live server.
- [README.md](README.md) — documentation index.
