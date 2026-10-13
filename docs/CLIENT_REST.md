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

### Through the proxy

A server that dials out to the standalone proxy is reached at `http(s)://<proxyHost>:<port>/<instanceId>/<path>`. `<path>` is the path the server itself uses, so `GET /song/search` becomes `GET /<instanceId>/song/search`. The proxy host, port, TLS flag and instance id are served by `GET /serverStats/proxyInfo` (`IServerStatsService.getProxyInfo()`, model `ProxyInfo` with `host`, `controlPort`, `ssl` and `id`). The websocket for the Kotlin RPC stays at `ws(s)://<proxyHost>:<port>/<instanceId>/rpc`.

Authentication, request and response bodies, `Range` requests, `HEAD`, Server-Sent Events and compression behave as with direct access. Only these paths are reachable:

- every generated route from [REST_API.md](REST_API.md)
- the hand-written `POST /authenticate`, `POST /refresh-token`, `POST /register`, `GET /userInfo`, `GET /sessions` and `DELETE /sessions/{sessionId}`
- `OPTIONS` for those paths, which answers the CORS preflight

Everything else answers `404` through the proxy: `/swagger`, `/api.json`, `/static`, the web UI, `/mcp`, the hand-written `/radio/**` stream routes and the Subsonic API at `/rest/**`. A path with a trailing slash is refused as it is on the server itself. A malformed request target answers `400`.

The proxy itself answers these statuses, with `Access-Control-Allow-Origin: *` when the request has an `Origin` header:

| Status | When |
|---|---|
| `404` | the instance id is `proxy` or `instances`, which the proxy reserves |
| `503` | no server is connected under the id, or the instance already has 128 requests open |
| `501` | the connected server does not support HTTP through the proxy |
| `431` | the request headers are larger than 65536 bytes |
| `400` | the request method is longer than 255 characters |
| `502` | the server or the connection to it failed before a response started |

The proxy closes the connection of a client that reads nothing for 10 seconds while response data is waiting. A reverse proxy in front of the Synara proxy must not buffer responses, or Server-Sent Events arrive late.

## Authentication

Two credentials exist. Details, lifetimes and the API-key scopes are in [AUTHENTICATION.md](AUTHENTICATION.md); the summary a client needs:

- **JWT.** `POST /authenticate` with `{"username": …, "password": …}` returns `{token, refreshToken, expiresAt}` (`expiresAt` is epoch milliseconds; see [API_CONSTANTS.md#authentication](API_CONSTANTS.md#authentication) for the access token's lifetime). `POST /refresh-token` with `{"refreshToken": …}` returns a fresh pair. Use these two hand-written routes.
- The generated `POST /auth/authenticate?username=&password=` reaches the same service but is **not** equivalent: the credentials travel in the query string (and therefore into logs and proxies), and a wrong password comes back as `400 Invalid username or password` — the RPC method throws `IllegalArgumentException`, which the REST layer maps to `400` — where `/authenticate` answers `401`. Handle both codes if you must call it.
- Send the token as `Authorization: Bearer <jwt>` **or** as the cookie `synara-auth=<jwt>`. The cookie is checked first and wins if both are present. The server never sets that cookie itself — a web client writes it (`document.cookie`) after logging in. Behind the proxy, scope the cookie with `Path=/<instanceId>/`, see [Through the proxy](#through-the-proxy).
- **API keys** (`?apiKey=`, header `X-API-Key`, or `Authorization: Bearer <key>`) are accepted only by `/radio/**` and `/mcp`, plus the Subsonic API at `/rest/**`, which takes a key with the `subsonic` scope in its `apiKey` parameter. They do not work for the generated services.

Routes marked `PUBLIC` in the route table need no credential at all: the handshake, the `/auth/*` and `/serverStats/*` routes, `GET /image/imageData/{id}`, `GET /animatedImage/imageData/{id}` and `GET /release/releaseImage/{releaseId}`.

Behind the proxy several instances share one origin. A cookie written with `path=/` is sent to every instance on that proxy, so the token of one server reaches the others. Write it with `Path=/<instanceId>/` instead, so the cookie string is `synara-auth=<jwt>; path=/<instanceId>/; SameSite=Strict`.

CORS allows any origin and any method, but **not** credentials, so a browser cannot send the cookie cross-origin. Serve a web client from the same origin as the server (or put both behind one reverse proxy) whenever you need cookie authentication — which you do for `<audio>`, `<img>` and `EventSource`, none of which can set a header.

## Headers

| Header | Send it | Meaning |
|---|---|---|
| `Authorization: Bearer <jwt>` | on every authenticated request | See above. |
| `X-Api-Version` | always | The value of `ApiVersion.CURRENT` you compiled against — see [API_CONSTANTS.md#api-version](API_CONSTANTS.md#api-version). Absent means the legacy version, and the server then reshapes responses accordingly. See [API_VERSIONING.md](API_VERSIONING.md). |
| `X-Ui-Schema-Version` | if you render server-driven UI | Omit it and every component arrives as `Fallback`. See [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md). |
| `Accept-Language` | if you show server text | e.g. `de-AT, de;q=0.9, en;q=0.5`; the highest-quality language wins, fallback `en`. |
| `X-Time-Zone` | optionally, if you show server text | An IANA time zone id such as `Europe/Berlin`. Times in server-driven UI are shown in this zone. Without it, or with an invalid id, they are shown in UTC. |
| `Content-Type: application/json` | on requests with a body | Bodies are always JSON. |

The code samples throughout this page and the rest of the docs send `X-Api-Version: 6` — the number current when they were written. A lower number still works against a newer server: the server shapes its response down to what that version understands, per [API_VERSIONING.md](API_VERSIONING.md).

## JSON conventions

Everything is encoded with one `kotlinx.serialization` configuration (`AppJson`), so:

- **Unknown keys are ignored** on the way in, and **defaults are always written** on the way out — a field with a default is present in the response, not omitted. A handful of fields are explicitly excluded from that rule and appear only when they are set: `audio` and `atmos` on `Song`/`UserSong` are absent when `null`, and `tags` is absent when empty.
- **UUIDs are strings**, `"7c9e…"`. Dates and times: `Instant`, `LocalDate`, `LocalDateTime` and `OffsetDateTime` are ISO-8601 strings; `PlatformDate` fields (`expiresAt`, `userSongCreatedAt`, …) are epoch-millisecond numbers; `Duration` is an ISO-8601 duration string. Timestamps that are documented as "Unix timestamp in milliseconds" are plain numbers.
- **Sealed hierarchies carry a `type` discriminator** with the serial name of the variant (`{"type": "Conflict", …}`, `{"type": "tile", …}`). The UI vocabulary is described in [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md).
- `ByteArray` in a **response** is sent as raw bytes, but `ByteArray` in a **request body** is JSON — an array of signed byte numbers (`PUT /user/profileImage` takes `[-119,80,78,71,…]`). There is no multipart upload.

Model field lists live in [MODELS.md](MODELS.md), anchored per type, e.g. [`UserSong`](MODELS.md#devdertypdatausersong) and [`PaginatedResponse`](MODELS.md#devdertypdatapaginatedresponse).

## How a route is derived

The REST layer is generated by `common-rpc/rest-compiler` from the RPC interfaces: the prefix comes from the interface name, the verb and path segment from the method name, `@RestGet`/`@RestPost`/`@RestPut`/`@RestDelete`/`@RestPath` override both, `type`/`…Id` parameters become path segments, and everything else is bound by type — a primitive as a query parameter, a non-primitive as the JSON body (or, with several non-primitive parameters, one wrapper object with a field per parameter). The full rule set and the parameter-binding table are in [REST_API.md#how-routes-are-derived](REST_API.md#how-routes-are-derived).

Two worked examples: `ISongService.rankedSearch` → `GET /song/search`, and `ISongService.setLikeLevel` → `PUT /song/likeLevel/{id}`.

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

Page through until `hasNextPage` is false. The default page size is whatever the interface declares — 50 on most library queries, 200 on the queue, 150 on the artist release feeds, while `getRecentReleases` returns up to 50 entries per release type per page (folded editions counting once) — so send `pageSize` explicitly if it matters to you.

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

Any method returning `Flow<T>` (other than `Flow<ByteArray>`) is an SSE endpoint: `Content-Type: text/event-stream`, `Cache-Control: no-cache`, one JSON object per `data:` line, no event names and no ids. The server also sends an SSE comment line, `: keep-alive`, once on connect and every 5 seconds after that. `EventSource` and other SSE libraries drop comments on their own, but a hand-written line reader must ignore lines starting with `:` instead of parsing them. The stream stays open until you close it or the server shuts down; `null` items are skipped. Reconnect yourself — there is no `Last-Event-ID` handling.

Whether the first event arrives immediately depends on the stream. `GET /listenBackup/observeState` emits the current state right away; `GET /queue/observeQueue` emits only after a write, so a fresh subscription stays silent until something actually changes — read `GET /queue/queueInfo` once for the initial state. `GET /clientRequest/connect?description=`, `GET /remoteControl/observeStatus/{sessionId}` (see [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md)) and `GET /change/observeChanges` behave like the latter, except `observeStatus` replays the last known status immediately. `observeChanges` only ever names the topic that changed (`ONLINE_DEVICES`, `HOME_CARDS`, `LISTENS`, `LISTENBRAINZ_STATUS`) and carries no data, so the client reads the actual state with the regular getter — `getOnlineDevices`, `getHomeCards`, `recentListens`/`recentArtists`/`recentAlbums`, or ListenBrainz's `getStatus`.

```bash
curl -N -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6' \
  'http://localhost:8080/queue/observeQueue'
```

```bash
curl -N -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6' -G \
  'http://localhost:8080/clientRequest/connect' \
  --data-urlencode 'description={"deviceName":"Desk","capabilities":["REMOTE_CONTROL","REMOTE_VOLUME"]}'
```

```ts
document.cookie = `synara-auth=${token}; path=/; SameSite=Strict`;
const events = new EventSource("/queue/observeQueue");
events.onmessage = (event) => {
  const info = JSON.parse(event.data);
  console.log(info.version, info.total);
};
```

```swift
var request = URLRequest(url: URL(string: "\(base)/queue/observeQueue")!)
request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
request.setValue("6", forHTTPHeaderField: "X-Api-Version")

let (stream, _) = try await URLSession.shared.bytes(for: request)
for try await line in stream.lines where line.hasPrefix("data: ") {
    let payload = Data(line.dropFirst(6).utf8)
    handle(try JSONDecoder().decode(QueueInfo.self, from: payload))
}
```

`EventSource` cannot send an `Authorization` header, so in the browser it depends on the `synara-auth` cookie and therefore on being same-origin.

## File routes

Methods annotated `@RestFileResponse` — `streamSong`, `streamSongAtmos`, `downloadSong`, `streamEpisode` — are registered for both **GET and HEAD**, answer with `Accept-Ranges: bytes` and `Content-Disposition: inline; filename="…"`, and run through Ktor's `PartialContent` (at most 10 ranges per request), so an ordinary `Range: bytes=…` request yields `206` with `Content-Range`. That is what makes seeking work in `<audio>`, `AVPlayer` and ExoPlayer. The header is always plain ASCII. `filename` holds the file name, and when the name has non-ASCII characters each of them is replaced by `_` there and a second parameter `filename*` holds the exact name, percent-encoded as UTF-8 (`filename*=utf-8''s%C3%A4nger.mp3`). A client that wants the exact name reads `filename*` when it is present and `filename` otherwise.

- `HEAD` returns the `Content-Length` and the content type the server resolved for the file, without a body; it is the cheapest way to learn a stream's size *and* its type.
- **The GET body's content type is not necessarily that type.** Only the HEAD response carries the resolved one; the GET (and ranged `206`) response is typed from the file extension by a generic table. MP3, M4A, OGG, WAV and AIFF files do arrive with an `audio/*` type, but FLAC and `.opus` files arrive as `application/octet-stream`. The same holds for podcast episodes: the server computes `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/ogg`, `audio/flac` or `audio/wav` from the extension, but that value reaches the HEAD response only, and a FLAC or Opus episode is `application/octet-stream` on GET. Learn the type from `HEAD`, or from the song's `audio.codec` — never from the streamed response.
- If the server cannot resolve a file for the request (a podcast episode it only relays, for instance), the same route falls back to a plain chunked byte stream without `Accept-Ranges`; seek with the `offset` query parameter instead.

Details, content types and the size endpoints are in [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md).

## Generated Kotlin clients

The Gradle module `:common-rpc:rest-client` offers the same services as the RPC stubs over this HTTP surface. For every `@Rpc` interface the build generates a class in the package `dev.dertyp.rpc.rest.client` that implements the interface and calls the routes of this page. The name is `Rest` plus the interface name without its leading `I`, so `IAlbumService` becomes `RestAlbumService`. A small hand-written runtime in the same package (`RestClientConfig`, `RestClient`, `RestRequest`, `RestException`) does the HTTP work. A Kotlin app can therefore use the `IAlbumService` type without caring whether the transport is the websocket or HTTP.

```kotlin
val config = RestClientConfig(
    httpClient = HttpClient(),
    baseUrl = { "http://localhost:8080" },
    token = { forceRefresh -> tokenStore.accessToken(refresh = forceRefresh) },
)
val albums: IAlbumService = RestAlbumService(config)
val album = albums.byId(albumId)
```

`baseUrl` and `token` are suspending lambdas that are read on every request. `baseUrl` may carry a path prefix. To reach a server through the proxy, return `"https://<proxyHost>:<port>/<instanceId>"` built from `ProxyInfo`. Every call, flow, file method and `<method>Url` builder then goes through the proxy, see [Through the proxy](#through-the-proxy). `token` receives `forceRefresh = true` when the server answered `401` and returns `null` when no token is available. `headers` is an optional block that adds further headers to every request. Every request carries `X-Api-Version` with the version the module was compiled against. Bodies and responses are JSON, and routes marked `PUBLIC` are sent without an `Authorization` header.

- **Errors.** A `401` is retried once with `token(true)`. A second `401` throws `RestException(401, …)`. A `400` throws `IllegalArgumentException` with the text of the response. Every other failure throws `RestException` with the status and the response text, or the status description when the body is empty. A `404` gives `null` for a method with a nullable return type and a `RestException` otherwise.
- **Flows.** A method returning `Flow<T>` is cold and opens one SSE request each time it is collected. There is no automatic reconnect, because the server sends no resume position. Collect again to start over, and read the current state with the regular getter first where [Server-Sent Events](#server-sent-events) says the stream stays silent until something changes. A `Flow<ByteArray>` method streams the raw response body.
- **File methods.** `streamSong`, `streamSongAtmos`, `downloadSong` and `streamEpisode` return a flow that sends its `GET` when it is collected. It passes `offset` as the query parameter and, when `offset` is above 0, as a `Range: bytes=<offset>-` header. It emits chunks of `chunkSize` bytes, the last one shorter. A `404` throws `RestException(404, …)` at collection time.
- **URL builders.** Every method `foo` with a REST route has a second method `fooUrl(...)` that returns the absolute URL of the call as a `String` and sends nothing. It takes the path and query parameters of the route in route order. A parameter that has a default may be left out, and the server then applies its default. For `POST`, `PUT` and `DELETE` routes the body is not part of the URL. A route marked `PUBLIC` needs no token. For every other route the caller adds the `Authorization` header or the `synara-auth` cookie itself, as described in [Authentication](#authentication). File routes answer byte ranges, so a player that is given the URL can seek, see [File routes](#file-routes). `RestClient.url(request)` is the runtime function behind the builders and returns a Ktor `Url`.

```kotlin
val url = RestSongService(config).downloadSongUrl(songId, quality = 320)
```

- **Members without a REST route** throw `UnsupportedOperationException` when called.

Known limits of the parameter encoding:

- A nullable query parameter whose default is not `null` cannot carry an explicit `null`. The client omits it, so the server uses the default.
- A non-null list or set parameter with a default cannot carry an empty collection, for the same reason.
- An empty string for a required query parameter counts as missing and answers `400`.

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
- **Forgetting `X-Api-Version`.** Without it the server treats you as a version-1 client: `streamSongAtmos` resolves to nothing (`404`) while songs still carry `atmos`, WAV and AIFF files are transcoded to FLAC before streaming, title tags are folded back into the song title and album edition tags into the album name, release and album editions arrive as separate entries, and server-driven UI arrives as `Fallback`. The complete list is the feature table in [API_CONSTANTS.md#features](API_CONSTANTS.md#features).
- **Sending a bare object where a multi-body route expects a wrapper.** Two `b:` parameters mean one JSON object with a field per parameter.
- **Expecting JSON error bodies.** Failures are plain text.
- **Using the header for media.** `<audio>`, `<img>`, `EventSource` and `AVPlayer` cannot set `Authorization`; use the `synara-auth` cookie on the same origin (behind the proxy with `Path=/<instanceId>/`), or a `PUBLIC` image route. There is no signed-URL or token-in-query mechanism for the generated services — API keys only open `/radio/**`, `/mcp` and the Subsonic API at `/rest/**`.
- **Assuming `404` means the route is wrong.** It usually means the method returned `null`.

## Next

- [CLIENT_GETTING_STARTED.md](CLIENT_GETTING_STARTED.md) — the first ten minutes against a live server.
- [REST_API.md](REST_API.md) — every route, its parameters and its response kind.
- [AUTHENTICATION.md](AUTHENTICATION.md) — tokens, sessions, API keys and capabilities.
- [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md) — audio, images, scrobbling, queue and multi-device state.
- [API_VERSIONING.md](API_VERSIONING.md) — what each `X-Api-Version` changes.
- [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md) — rendering the server's component trees.
- [README.md](README.md) — documentation index.
