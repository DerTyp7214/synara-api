# REST for Client Developers

Synara speaks two protocols with the same vocabulary: a Kotlin RPC websocket and a plain HTTP/JSON surface. This page is for developers writing a client in **any** language — a web app, an iOS app, a desktop player, a script — against that HTTP surface. It explains where the routes come from, how parameters and responses are shaped, and how to authenticate; it does not list the routes themselves, because [REST_API.md](REST_API.md) is generated from the same source the server is generated from. Kotlin clients should read [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md) instead and use the typed RPC stubs.

## Base URL and discovery

Every route is mounted at the root of the server — there is no `/api` prefix. With the default configuration the server listens on port 8080, so `GET /user/me` is `http://localhost:8080/user/me`.

| Resource | What it is |
|---|---|
| `/swagger` | Swagger UI of the live server. |
| `/api.json` | OpenAPI document of the live server, including the hand-written routes. |
| [REST_API.md](REST_API.md) | The generated route table in this repository, one section per service. |
| `GET /handshake/handshake` | Reachability probe; returns `secure`, `sslSupported`, `apiVersion` and `uiSchemaVersion`. Needs no token. |

The OpenAPI document is the authority for the server you are actually talking to; `REST_API.md` is the authority for the version in this repository.

## Authentication

Two credentials exist. Details, lifetimes and the API-key scopes are in [AUTHENTICATION.md](AUTHENTICATION.md); the summary a client needs:

- **JWT.** `POST /authenticate` with `{"username": …, "password": …}` returns `{token, refreshToken, expiresAt}` (`expiresAt` is epoch milliseconds; see [API_CONSTANTS.md#authentication](API_CONSTANTS.md#authentication) for the access token's lifetime). `POST /refresh-token` with `{"refreshToken": …}` returns a fresh pair. Use these two hand-written routes.
- The generated `POST /auth/authenticate?username=&password=` reaches the same service but is **not** equivalent: the credentials travel in the query string (and therefore into logs and proxies), and a wrong password comes back as `400 Invalid username or password` — the RPC method throws `IllegalArgumentException`, which the REST layer maps to `400` — where `/authenticate` answers `401`. Handle both codes if you must call it.
- Send the token as `Authorization: Bearer <jwt>` **or** as the cookie `synara-auth=<jwt>`. The cookie is checked first and wins if both are present. The server never sets that cookie itself — a web client writes it (`document.cookie`) after logging in.
- **API keys** (`?apiKey=`, header `X-API-Key`, or `Authorization: Bearer <key>`) are accepted only by `/radio/**` and `/mcp`. They do not work for the generated services.

Routes marked `PUBLIC` in the route table need no credential at all: the handshake, `GET /image/imageData/{id}`, `GET /animatedImage/imageData/{id}` and `GET /release/releaseImage/{releaseId}`.

CORS allows any origin and any method, but **not** credentials, so a browser cannot send the cookie cross-origin. Serve a web client from the same origin as the server (or put both behind one reverse proxy) whenever you need cookie authentication — which you do for `<audio>`, `<img>` and `EventSource`, none of which can set a header.

## Headers

| Header | Send it | Meaning |
|---|---|---|
| `Authorization: Bearer <jwt>` | on every authenticated request | See above. |
| `X-Api-Version` | always | The value of `ApiVersion.CURRENT` you compiled against — see [API_CONSTANTS.md#api-version](API_CONSTANTS.md#api-version). Absent means the legacy version, and the server then reshapes responses accordingly. See [API_VERSIONING.md](API_VERSIONING.md). |
| `X-Ui-Schema-Version` | if you render server-driven UI | Omit it and every component arrives as `Fallback`. See [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md). |
| `Accept-Language` | if you show server text | e.g. `de-AT, de;q=0.9, en;q=0.5`; the highest-quality language wins, fallback `en`. |
| `Content-Type: application/json` | on requests with a body | Bodies are always JSON. |

The code samples throughout this page and the rest of the docs send `X-Api-Version: 6` — the number current when they were written. A lower number still works against a newer server: the server shapes its response down to what that version understands, per [API_VERSIONING.md](API_VERSIONING.md).

## JSON conventions

Everything is encoded with one `kotlinx.serialization` configuration (`AppJson`), so:

- **Unknown keys are ignored** on the way in, and **defaults are always written** on the way out — a field with a default is present in the response, not omitted. A handful of fields are explicitly excluded from that rule (the deprecated audio fields on `Song`/`UserSong`, for instance) and appear only when they are set.
- **UUIDs are strings**, `"7c9e…"`. Dates and times: `Instant`, `LocalDate`, `LocalDateTime` and `OffsetDateTime` are ISO-8601 strings; `PlatformDate` fields (`expiresAt`, `userSongCreatedAt`, …) are epoch-millisecond numbers; `Duration` is an ISO-8601 duration string. Timestamps that are documented as "Unix timestamp in milliseconds" are plain numbers.
- **Sealed hierarchies carry a `type` discriminator** with the serial name of the variant (`{"type": "Conflict", …}`, `{"type": "tile", …}`). The UI vocabulary is described in [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md).
- `ByteArray` in a **response** is sent as raw bytes, but `ByteArray` in a **request body** is JSON — an array of signed byte numbers (`PUT /user/profileImage` takes `[-119,80,78,71,…]`). There is no multipart upload.

Model field lists live in [MODELS.md](MODELS.md), anchored per type, e.g. [`UserSong`](MODELS.md#devdertypdatausersong) and [`PaginatedResponse`](MODELS.md#devdertypdatapaginatedresponse).

## How a route is derived

The REST layer is generated by `common-rpc/rest-compiler` from the RPC interfaces: the prefix comes from the interface name, the verb and path segment from the method name, `@RestGet`/`@RestPost`/`@RestPut`/`@RestDelete`/`@RestPath` override both, `type`/`…Id` parameters become path segments, and everything else is bound by type — a primitive as a query parameter, a non-primitive as the JSON body (or, with several non-primitive parameters, one wrapper object with a field per parameter). The full rule set and the parameter-binding table are in [REST_API.md#how-routes-are-derived](REST_API.md#how-routes-are-derived).

Two worked examples: `ISongService.rankedSearch` → `GET /song/search`, and `ISongService.setLiked` → `PUT /song/liked/{id}`.

The multi-body case is easy to get wrong. `ICoverGenerationService.applyCover(target, params)` is `POST /coverGeneration/applyCover` with

```json
{"target": {"…": "…"}, "params": {"…": "…"}}
```

— not the bare `target` object. The route table marks every body parameter with `b:`, so a route with two `b:` entries takes the wrapper object. A `?` in the route table means the parameter has a default and may be omitted.

Values are parsed strictly: enums case-insensitively by name, booleans as `true`/`false`, UUIDs and `Instant`s in their canonical form. Anything else is a `400`.

Defaults are the ones declared on the interface: omit a parameter and the server calls the method with its Kotlin default. That also means an omitted parameter is *not* the same as an explicit empty value.

## Pagination

List routes take `page` (0-based) and `pageSize` as query parameters and answer with [`PaginatedResponse`](MODELS.md#devdertypdatapaginatedresponse):

```json
{"data": [], "page": 0, "total": 1042, "pageSize": 50, "hasNextPage": true}
```

Page through until `hasNextPage` is false. The default page size is whatever the interface declares — 50 on most library queries, 200 on the queue, 150 on release feeds — so send `pageSize` explicitly if it matters to you.

## Responses and status codes

| Return type of the method | Response |
|---|---|
| a value | `application/json` |
| `Unit` | `200` with an empty body |
| `ByteArray` | the raw bytes; `Content-Type` sniffed from the magic bytes when the payload is an image or a video (plus `Content-Disposition: inline`), otherwise `application/octet-stream` |
| `Flow<ByteArray>` | a raw byte stream |
| `Flow<T>` | Server-Sent Events |
| `@RestFileResponse` | a file response with range support, see below |

| Status | When |
|---|---|
| `200` | success (`206` on a satisfied `Range` request) |
| `400` | a parameter failed to bind, a body was not a JSON object, a required parameter was missing, or the method threw `IllegalArgumentException` |
| `401` | the route needs a user and no valid token was presented |
| `403` | the user lacks the capability or admin flag the method requires |
| `404` | the method returned `null` (unknown id, no Atmos variant, …) |
| `500` | anything else; the body carries the exception message, and an error escaping the route layer is answered as `500: <cause>` |

Error bodies are **plain text**, not JSON. Treat a `404` as "no such thing" rather than "route does not exist".

## Server-Sent Events

Any method returning `Flow<T>` (other than `Flow<ByteArray>`) is an SSE endpoint: `Content-Type: text/event-stream`, `Cache-Control: no-cache`, one JSON object per `data:` line, no event names and no ids. The stream stays open until you close it or the server shuts down; `null` items are skipped. Reconnect yourself — there is no `Last-Event-ID` handling.

Whether the first event arrives immediately depends on the stream. `GET /scrobble/recentListensFlow?limit=5` emits the current state right away; `GET /queue/observeQueue` emits only after a write, so a fresh subscription stays silent until something actually changes — read `GET /queue/queueInfo` once for the initial state.

```bash
curl -N -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6' \
  'http://localhost:8080/scrobble/recentListensFlow?limit=5'
```

```ts
document.cookie = `synara-auth=${token}; path=/; SameSite=Strict`;
const events = new EventSource("/scrobble/recentListensFlow?limit=5");
events.onmessage = (event) => {
  const listens = JSON.parse(event.data);
  console.log(listens.nowPlaying?.song.title, listens.recent.length);
};
```

```swift
var request = URLRequest(url: URL(string: "\(base)/scrobble/recentListensFlow?limit=5")!)
request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
request.setValue("6", forHTTPHeaderField: "X-Api-Version")

let (stream, _) = try await URLSession.shared.bytes(for: request)
for try await line in stream.lines where line.hasPrefix("data: ") {
    let payload = Data(line.dropFirst(6).utf8)
    handle(try JSONDecoder().decode(RecentListens.self, from: payload))
}
```

`EventSource` cannot send an `Authorization` header, so in the browser it depends on the `synara-auth` cookie and therefore on being same-origin.

## File routes

Methods annotated `@RestFileResponse` — `streamSong`, `streamSongAtmos`, `downloadSong`, `streamEpisode` — are registered for both **GET and HEAD**, answer with `Accept-Ranges: bytes` and `Content-Disposition: inline; filename="…"`, and run through Ktor's `PartialContent` (at most 10 ranges per request), so an ordinary `Range: bytes=…` request yields `206` with `Content-Range`. That is what makes seeking work in `<audio>`, `AVPlayer` and ExoPlayer.

- `HEAD` returns the `Content-Length` and the content type the server resolved for the file, without a body; it is the cheapest way to learn a stream's size *and* its type.
- **The GET body's content type is not that type.** Only the HEAD response carries the resolved one; the GET (and ranged `206`) response is produced by Ktor from the file name, so a FLAC song arrives as `application/octet-stream`. The same holds for podcast episodes: the server computes `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/ogg`, `audio/flac` or `audio/wav` from the extension, but that value only reaches the HEAD response. Learn the type from `HEAD`, or from the song's `audio.codec` — never from the streamed response.
- If the server cannot resolve a file for the request (a podcast episode it only relays, for instance), the same route falls back to a plain chunked byte stream without `Accept-Ranges`; seek with the `offset` query parameter instead.

Details, content types and the size endpoints are in [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md).

## Worked samples

### Log in

```bash
curl -X POST http://localhost:8080/authenticate \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret"}'
```

```swift
struct AuthResponse: Decodable {
    let token: String
    let refreshToken: String
    let expiresAt: Int64
}

func login(base: URL, username: String, password: String) async throws -> AuthResponse {
    var request = URLRequest(url: base.appending(path: "authenticate"))
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = try JSONEncoder().encode(["username": username, "password": password])
    let (data, _) = try await URLSession.shared.data(for: request)
    return try JSONDecoder().decode(AuthResponse.self, from: data)
}
```

```ts
const response = await fetch(`${base}/authenticate`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username, password }),
});
const { token, refreshToken, expiresAt } = await response.json();
```

### The current user

```bash
curl -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6' \
  http://localhost:8080/user/me
```

```swift
var request = URLRequest(url: base.appending(path: "user/me"))
request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
request.setValue("6", forHTTPHeaderField: "X-Api-Version")
let (data, _) = try await URLSession.shared.data(for: request)
let me = try JSONDecoder().decode(User.self, from: data)
```

```ts
const me = await fetch(`${base}/user/me`, {
  headers: { Authorization: `Bearer ${token}`, "X-Api-Version": "6" },
}).then((r) => r.json());
```

### Search songs

`ISongService.rankedSearch` → `GET /song/search`, with `query` and `explicit` required and `page`, `pageSize`, `liked` optional.

```bash
curl -G http://localhost:8080/song/search \
  -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6' \
  --data-urlencode 'query=daft punk' \
  --data-urlencode 'explicit=true' \
  --data-urlencode 'pageSize=20'
```

```swift
var components = URLComponents(url: base.appending(path: "song/search"), resolvingAgainstBaseURL: false)!
components.queryItems = [
    URLQueryItem(name: "query", value: "daft punk"),
    URLQueryItem(name: "explicit", value: "true"),
    URLQueryItem(name: "pageSize", value: "20"),
]
var request = URLRequest(url: components.url!)
request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
request.setValue("6", forHTTPHeaderField: "X-Api-Version")
let (data, _) = try await URLSession.shared.data(for: request)
let page = try JSONDecoder().decode(PaginatedResponse<UserSong>.self, from: data)
```

```ts
const params = new URLSearchParams({ query: "daft punk", explicit: "true", pageSize: "20" });
const page = await fetch(`${base}/song/search?${params}`, {
  headers: { Authorization: `Bearer ${token}`, "X-Api-Version": "6" },
}).then((r) => r.json());
```

### Read a stream

```bash
curl -I -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6' \
  "http://localhost:8080/song/streamSong/$SONG_ID"

curl -r 0-65535 -o head.flac -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6' \
  "http://localhost:8080/song/streamSong/$SONG_ID"
```

```swift
var request = URLRequest(url: base.appending(path: "song/streamSong/\(songId)"))
request.httpMethod = "HEAD"
request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
request.setValue("6", forHTTPHeaderField: "X-Api-Version")
let (_, response) = try await URLSession.shared.data(for: request)
let size = (response as! HTTPURLResponse).expectedContentLength
```

```ts
const head = await fetch(`${base}/song/streamSong/${songId}`, {
  method: "HEAD",
  headers: { Authorization: `Bearer ${token}`, "X-Api-Version": "6" },
});
const size = Number(head.headers.get("content-length"));
```

For actual playback the player element does the fetching and cannot carry the header — see [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md).

## Common mistakes

- **Forgetting a required parameter that looks optional.** `explicit` on `/song/search`, `/song/allSongs`, `/song/likedSongs` and friends has no default; leaving it out is `400 Missing required parameter 'explicit'`. The route table marks optional parameters with `?`.
- **Forgetting `X-Api-Version`.** Without it the server treats you as a version-1 client: `audio`/`atmos` are replaced by the flat `sampleRate`/`bitsPerSample`/`bitRate`/`fileSize` fields, Atmos is hidden entirely, title tags are folded back into the title, and server-driven UI arrives as `Fallback`.
- **Sending a bare object where a multi-body route expects a wrapper.** Two `b:` parameters mean one JSON object with a field per parameter.
- **Expecting JSON error bodies.** Failures are plain text.
- **Using the header for media.** `<audio>`, `<img>`, `EventSource` and `AVPlayer` cannot set `Authorization`; use the `synara-auth` cookie on the same origin, or a `PUBLIC` image route. There is no signed-URL or token-in-query mechanism for the generated services — API keys only open `/radio/**` and `/mcp`.
- **Assuming `404` means the route is wrong.** It usually means the method returned `null`.

## Next

- [CLIENT_GETTING_STARTED.md](CLIENT_GETTING_STARTED.md) — the first ten minutes against a live server.
- [REST_API.md](REST_API.md) — every route, its parameters and its response kind.
- [AUTHENTICATION.md](AUTHENTICATION.md) — tokens, sessions, API keys and capabilities.
- [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md) — audio, images, scrobbling, queue and multi-device state.
- [API_VERSIONING.md](API_VERSIONING.md) — what each `X-Api-Version` changes.
- [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md) — rendering the server's component trees.
- [README.md](README.md) — documentation index.
