# Getting Started as a Client

This page is for a developer who has never seen Synara and wants to talk to a server from their own app. It lists the four surfaces a server exposes, helps you pick one, shows how to point at a server you can experiment with, explains the handful of conventions that run through every response, and walks through a first login-and-search in both the Kotlin RPC SDK and plain `curl`. The exact method and route reference lives in the generated [RPC_SERVICES.md](RPC_SERVICES.md), [MODELS.md](MODELS.md) and [REST_API.md](REST_API.md); this page only gets you to your first successful call.

## What a server exposes

A single Synara process serves four independent API surfaces, all on the same host and port ([Routing.kt](../server/src/main/kotlin/dev/dertyp/Routing.kt)):

| Surface | Endpoint | Protocol | Read more |
|---|---|---|---|
| **Kotlin RPC** | `/rpc`, `/rpc/auth`, `/rpc/services` | kotlinx-rpc over WebSockets, CBOR frames | [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md) |
| **REST** | one route per service method, e.g. `/song/search` | HTTP, JSON bodies, SSE for streams | [CLIENT_REST.md](CLIENT_REST.md), [REST_API.md](REST_API.md) |
| **Subsonic** | `/rest/...` | Subsonic / OpenSubsonic XML or JSON | below |
| **MCP** | `POST /mcp` | JSON-RPC 2.0, streamable HTTP | [MCP.md](MCP.md) |

Two more things are not surfaces of their own but shape what you build:

- **Server-driven UI** — the server (and its plugins) can describe whole screens that your client renders with native widgets. Available through `IUiService` on both RPC and REST. See [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md).
- **OpenAPI** — the REST surface documents itself at `/api.json`, with Swagger UI at `/swagger`.

## Which surface to pick

| | Kotlin RPC | REST | Subsonic | MCP |
|---|---|---|---|---|
| Language | Kotlin / Kotlin Multiplatform only | any | any | any |
| Coverage | every service, every method | every service, generated from the same interfaces | a fixed classic media API | listen history and statistics, read-only |
| Typed models | yes, the same `data class`es the server uses | you write or generate them | Subsonic's own shapes | JSON-RPC tool results |
| Streams | `Flow<T>` | Server-Sent Events | — | — |
| Auth | JWT bearer on the WebSocket upgrade | JWT bearer header or `synara-auth` cookie | Subsonic credentials or an API key with the `subsonic` scope | API key with the `mcp` scope |
| Best for | first-party apps on Android, iOS, macOS, desktop | web front-ends, scripts, integrations in other languages | reusing an existing Subsonic player | AI assistants |

The rule of thumb: if you are writing Kotlin, use the RPC SDK — it is the surface the first-party clients use, and you get the models, the reconnect logic and the token refresh for free. Everything else goes over REST, unless an existing Subsonic player is the whole point.

The Subsonic API is mounted at `/rest` by a built-in plugin ([SubsonicRoutes.kt](../server/src/main/kotlin/dev/dertyp/services/subsonic/SubsonicRoutes.kt)). It accepts the classic `u`+`t`+`s` token, `u`+`p` password (optionally `enc:`-hex), or an `apiKey` parameter holding the `subsonic` scope ([SubsonicAuth.kt](../server/src/main/kotlin/dev/dertyp/services/subsonic/SubsonicAuth.kt)); every route also answers to `POST` and to a `.view` suffix.

## Prerequisites

- A running Synara server, or the mock server (below).
- For REST, Subsonic or MCP: any HTTP client.
- For Kotlin RPC: a Kotlin Multiplatform build, and the `common-rpc` module added as a git submodule. Setup is in [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md).
- An account on that server. There is no self-registration: the **first** account is seeded on startup from the `client.id` / `client.secret` configuration properties — the `CLIENT_ID` and `CLIENT_SECRET` environment variables, documented in [ENVIRONMENT_VARIABLES.md](ENVIRONMENT_VARIABLES.md) as the initial admin username and password — and that user is an admin. The row is inserted with `insertIgnore` ([DatabaseManager.kt](../server/src/main/kotlin/dev/dertyp/services/DatabaseManager.kt)), so it is created once on the first run and later changes to the variables do not overwrite it. Every further account is created by that admin through `POST /register` or `IUserService.createUser`.

## Pointing at a server

The quickest way to see real shapes without owning a server is the **mock server**, which registers every RPC service with generated dummy data and mounts the same REST routes:

```bash
docker run -p 8081:8081 ghcr.io/dertyp7214/synara-mock:latest-dev
```

It listens on port `8081`, serves `/rpc`, `/rpc/auth` and `/rpc/services`, and accepts any `Authorization` header without validating it — so you can exercise everything except real authentication. Details and limits are in [MOCK_SERVER.md](MOCK_SERVER.md).

Against a real server, the first call worth making is the handshake, which tells you the API version the server speaks, whether it supports server-driven UI and whether TLS is available:

```bash
curl http://localhost:8080/handshake/handshake
```

An observed response, from a server built at the same point in time as this page:

```json
{"secure":false,"sslSupported":false,"apiVersion":6,"uiSchemaVersion":1}
```

Running a server of your own is covered in [DEVELOPMENT.md](DEVELOPMENT.md) and the project [README](../README.md).

## Five conventions

These hold across both RPC and REST and are worth internalising before you read any service reference.

1. **Ids are UUIDs.** In JSON they are the usual hyphenated strings; over CBOR the same value is encoded as a 16-byte string, because the RPC transport swaps `UUIDSerializer` for `UUIDByteSerializer` ([Serializers.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/serializers/Serializers.kt)). If you write your own CBOR decoder, expect bytes, not text.
2. **Timestamps come in two shapes.** A `PlatformDate` field (`AuthenticationResponse.expiresAt`, `UserSong.userSongCreatedAt`) is epoch milliseconds as a `Long`; a `PlatformInstant` field is an ISO-8601 string. Plain durations, such as `Song.duration` and `audioStartMs`, are milliseconds. The model reference in [MODELS.md](MODELS.md) names the type per field.
3. **Lists are paginated.** Any method with `page` and `pageSize` returns [`PaginatedResponse`](MODELS.md#devdertypdatapaginatedresponse): `data`, `page` (**0-based**), `total`, `pageSize` and `hasNextPage`. Page through until `hasNextPage` is false rather than computing page counts yourself.
4. **`explicit` is a required filter, not a preference.** Most song and album queries (`allSongs`, `likedSongs`, `rankedSearch`, `byColor`, …) take an `explicit: Boolean` with no default: pass `false` to hide explicit tracks, `true` to include them. Whatever your app's setting is, it belongs in that parameter.
5. **Covers are ids plus a blur hash.** Songs, albums, artists and playlists carry `coverId` and `blurHash` — never an image URL. Render the blur hash immediately, then fetch the bytes from the public route `GET /image/imageData/{id}?size=<px>` (`size=0` or omitted means original), or over RPC via `IImageService.getImageData`. See [RPC_SERVICES.md](RPC_SERVICES.md#devdertypservicesiimageservice).

## Headers to always send

| Header | Value | Why |
|---|---|---|
| `X-Api-Version` | the `ApiVersion.CURRENT` you built against | Without it the server treats you as the legacy version and strips newer fields. See [API_VERSIONING.md](API_VERSIONING.md) and [API_CONSTANTS.md#api-version](API_CONSTANTS.md#api-version) for the actual values. |
| `X-Ui-Schema-Version` | `UiSchemaVersion.CURRENT` | Only needed if you render server-driven UI; omitting it turns every component into `Fallback`. See [API_CONSTANTS.md#ui-schema-version](API_CONSTANTS.md#ui-schema-version). |
| `Accept-Language` | e.g. `de-AT, de;q=0.9, en;q=0.5` | Server-driven UI text arrives translated; default is `en`. |
| `Authorization` | `Bearer <token>` | Everything except the handshake, the stats service, the auth service and the public image routes. See [AUTHENTICATION.md](AUTHENTICATION.md). |
| `User-Agent` | something identifying your app | It is stored as the session's name, so it is what the user sees in their session list. |

On the RPC surface, headers are read at WebSocket upgrade time — changing the app language means reconnecting.

## Hello Synara — Kotlin RPC

`createRpcHttpClient` builds a Ktor client with WebSockets, the kRPC CBOR codec, the `X-Api-Version` default header and a `Synara/<version> (<platform>)` user agent. `BaseRpcServiceManager` then owns the connection; you only supply storage for the URL and the tokens. A complete minimal subclass is in [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md) — assume `SimpleRpcManager` from there:

```kotlin
import dev.dertyp.rpc.createRpcHttpClient
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserService

suspend fun helloSynara() {
    val manager = SimpleRpcManager(
        client = createRpcHttpClient("1.0.0"),
        host = "localhost",
        port = 8080,
        ssl = false,
    )

    manager.login("alice", "hunter2")

    val me = manager.getService<IUserService>().me()
    println("${me.displayName ?: me.username} — admin=${me.isAdmin}")

    val hits = manager.getService<ISongService>().rankedSearch(
        page = 0,
        pageSize = 20,
        query = "boards of canada",
        explicit = true,
    )
    hits.data.forEach { println("${it.title} — ${it.artists.joinToString { a -> a.name }}") }
    println("page ${hits.page} of ${hits.total}, more=${hits.hasNextPage}")
}
```

`login` is a two-liner on the sample subclass: it calls `getAuthService().authenticate(username, password)` — which connects to the public `/rpc/auth` endpoint — and hands the `AuthenticationResponse` to `updateAuth`, the hook where your subclass persists `token`, `refreshToken` and `expiresAt`. `getService<T>()` then talks to the authenticated `/rpc/services` endpoint with the stored token attached, and the manager refreshes the token on its own whenever `isTokenExpired()` says so.

## Hello Synara — REST

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/authenticate \
  -H 'Content-Type: application/json' \
  -H 'X-Api-Version: 6' \
  -d '{"username":"alice","password":"hunter2"}' | jq -r .token)

curl -s http://localhost:8080/user/me \
  -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6'

curl -s -G http://localhost:8080/song/search \
  --data-urlencode 'query=boards of canada' \
  --data-urlencode 'explicit=true' \
  -H "Authorization: Bearer $TOKEN" -H 'X-Api-Version: 6'
```

`POST /authenticate` is the hand-written login route and takes a JSON body; it answers with `token`, `refreshToken` and `expiresAt` (epoch millis). `GET /user/me` is `IUserService.me` and `GET /song/search` is `ISongService.rankedSearch` — the route naming, the query-vs-path parameter rules and the SSE streams are described in [CLIENT_REST.md](CLIENT_REST.md), and every route is listed in [REST_API.md](REST_API.md).

## Where next

| You want to… | Read |
|---|---|
| Build a Kotlin or KMP client | [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md) |
| Call the API from another language | [CLIENT_REST.md](CLIENT_REST.md), [REST_API.md](REST_API.md) |
| Log in, refresh, manage sessions, use API keys | [AUTHENTICATION.md](AUTHENTICATION.md) |
| Know what `X-Api-Version` changes | [API_VERSIONING.md](API_VERSIONING.md) |
| Play or download audio | [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md) |
| Render screens the server describes | [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md) |
| Store your client's settings on the server | [CLIENT_SETTINGS.md](CLIENT_SETTINGS.md) |
| Look up a method or a model | [RPC_SERVICES.md](RPC_SERVICES.md), [MODELS.md](MODELS.md) |
| Understand how the server is put together | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Run a server yourself | [DEVELOPMENT.md](DEVELOPMENT.md), [../README.md](../README.md) |
| Find every other document | [README.md](README.md), the documentation index |
