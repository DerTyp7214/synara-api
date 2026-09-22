# Kotlin RPC Client Guide

This page is for developers writing a Synara client in Kotlin or Kotlin Multiplatform. It covers adding the `common-rpc` SDK to your build, creating the HTTP client, implementing the one class you have to write yourself (`BaseRpcServiceManager`), logging in, obtaining services, consuming `Flow`s that survive a dropped connection, validating a server address and falling back from TLS, reading the handshake, and what failures look like. The method-by-method reference is generated into [RPC_SERVICES.md](RPC_SERVICES.md) and [MODELS.md](MODELS.md); if you have not read [CLIENT_GETTING_STARTED.md](CLIENT_GETTING_STARTED.md) yet, start there.

## Adding the SDK

The SDK is the `common-rpc` Kotlin Multiplatform module. It is not published to a Maven repository — clients consume it as a git submodule of the same upstream repository the server uses ([.gitmodules](../.gitmodules)):

```bash
git submodule add git@github.com:DerTyp7214/synara-common-rpc.git common-rpc
```

Then include the module and its two KSP processors in your `settings.gradle.kts` (the module's own build script adds `:common-rpc:compiler` and `:common-rpc:doc-compiler` as `ksp` dependencies, so both have to be part of the build):

```kotlin
include(":common-rpc")
include(":common-rpc:compiler")
include(":common-rpc:doc-compiler")
```

and depend on it from the module that talks to the server:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":common-rpc"))
        }
    }
}
```

`api` rather than `implementation` if your module exposes Synara models in its own API — the first-party KMP client does exactly that, and additionally `export(project(":common-rpc"))` in each `binaries.framework { }` block so the models are visible from Swift.

### What the module brings with it

[common-rpc/build.gradle.kts](../common-rpc/build.gradle.kts) declares:

- **Targets**: `jvm`, Android (only when an SDK is present — gated on the `synara.includeAndroid` property or a `local.properties` file), `iosX64`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `mingwX64` and `linuxX64`. Every native target also produces a static library named `common_rpc`, which is how the non-Kotlin clients link against it.
- **Coordinates**: group `de.dertyp7214`, version `1.0.0`.
- **Transitive API**: `kotlinx-rpc-core`, the kRPC Ktor client, `ktor-client-core` and the kotlinx-serialization core/JSON/CBOR artifacts — you do not need to declare those again.

The build script resolves its dependencies through the *consuming* project's version catalog, so your `gradle/libs.versions.toml` must define the plugin aliases `kotlin-multiplatform`, `kotlin-serialization`, `kotlinx-rpc`, `ksp` and `kover`, and the library aliases the script references (`kotlinx-rpc-core`, `kotlinx-rpc-krpc-ktor-client`, `kotlinx-rpc-krpc-serialization-cbor`, `ktor-client-core`, `ktor-client-cio`, `kotlinx-serialization-core`/`-json`/`-cbor`, `kotlinx-coroutines-test`). Copying those entries out of a working client's catalog is the fastest way to get the module to configure.

## The HTTP client

One factory builds everything the transport needs ([RpcClientFactory.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/rpc/RpcClientFactory.kt)):

```kotlin
import dev.dertyp.rpc.createRpcHttpClient

val client = createRpcHttpClient("1.0.0")
```

It returns a Ktor `HttpClient` that

- installs `UserAgent` with `Synara/<appVersion> (<platform>)` — this string becomes the name of the session the server records, so pass your real app version;
- sets `X-Api-Version` on every request through `defaultRequest { apiVersionHeader() }`;
- installs `WebSockets` and the `Krpc` plugin with CBOR serialization (`AppCbor`);
- calls `initializeServiceRegistry()`, the KSP-generated registry that maps service names to their `KClass` — this is why the KSP processors have to be in your build.

Create it once and keep it for the lifetime of the app.

## The service manager

`BaseRpcServiceManager` ([BaseRpcServiceManager.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/rpc/BaseRpcServiceManager.kt)) owns everything connection-shaped: which URL to dial, when to refresh the token, reconnecting, caching service stubs. You subclass it and supply storage. These are the members you must implement:

| Member | Contract |
|---|---|
| `suspend fun getRpcUrl(): String?` | Base URL with a `ws://` or `wss://` scheme, without a trailing slash — the manager appends `/rpc`, `/rpc/auth` and `/rpc/services` to it. A path prefix is allowed (that is how a proxy id is carried). `null` means "not configured yet". |
| `suspend fun setRpcUrl(host: String, port: Int, ssl: Boolean, path: String)` | Called by the manager when it demotes a `wss://` URL to `ws://` after a TLS failure. Persist the change. |
| `fun getAuthToken(): String?` | The current JWT access token. |
| `fun getRefreshToken(): String?` | The current refresh token, or `null` if there is none. |
| `fun isTokenExpired(): Boolean` | Cheap, synchronous, no I/O — it is consulted before every connection. Compare the stored `expiresAt` with now. |
| `fun isAuthenticated(): Boolean` | Whether credentials exist at all. Guards the "session expired" path and stops repeated logout callbacks. |
| `suspend fun updateAuth(response: AuthenticationResponse)` | Persist a fresh `token` / `refreshToken` / `expiresAt` after a login or a refresh. |
| `suspend fun handleAuthFailure(reason: Throwable?)` | The session is gone for good: clear credentials and send the user back to the login screen. |
| `val sslConfirmed: Boolean` | Whether TLS has ever worked against this server. Persisted, not per-run. |
| `suspend fun setSslConfirmed(value: Boolean)` | Store that flag. |

Optional overrides worth knowing: `supportsSsl` (default `true`; set `false` on a platform without TLS to skip the probe entirely), `uiLocale()` (default `null`, see *Locale* below), `onServerReachable()` / `onServerUnreachable()` (default: flip the `isServerReachable` flow), and the classifiers `isAuthException`, `isTransportException`, `isRefreshRejected`, `isSslException`.

### A complete minimal subclass

Tokens in memory, URL in fields. Everything a real client adds — DataStore, Keychain, a login-state flow — hangs off the same ten members.

```kotlin
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.rpc.BaseRpcServiceManager
import dev.dertyp.toEpochMilliseconds
import io.ktor.client.HttpClient

class SimpleRpcManager(
    client: HttpClient,
    private var host: String,
    private var port: Int,
    private var ssl: Boolean,
) : BaseRpcServiceManager(client) {

    private var auth: AuthenticationResponse? = null
    private var sslEverWorked = false

    override suspend fun getRpcUrl(): String = "${if (ssl) "wss" else "ws"}://$host:$port"

    override suspend fun setRpcUrl(host: String, port: Int, ssl: Boolean, path: String) {
        this.host = host
        this.port = port
        this.ssl = ssl
    }

    override fun getAuthToken(): String? = auth?.token

    override fun getRefreshToken(): String? = auth?.refreshToken

    override fun isTokenExpired(): Boolean {
        val expiresAt = auth?.expiresAt ?: return true
        return expiresAt.toEpochMilliseconds() < currentTimeMillis() + 60_000
    }

    override fun isAuthenticated(): Boolean = auth != null

    override suspend fun updateAuth(response: AuthenticationResponse) {
        auth = response
    }

    override suspend fun handleAuthFailure(reason: Throwable?) {
        auth = null
        clear()
    }

    override val sslConfirmed: Boolean get() = sslEverWorked

    override suspend fun setSslConfirmed(value: Boolean) {
        sslEverWorked = value
    }

    suspend fun login(username: String, password: String) {
        updateAuth(getAuthService().authenticate(username, password))
    }
}
```

Two details in there are deliberate. `isTokenExpired()` returns `true` when there is no token at all, so the first `getService` call takes the "authenticate or fail loudly" path instead of dialing with a null token. And the one-minute margin — the same one the first-party client uses — means a token is refreshed slightly before it dies, rather than after a call has already failed. An even smaller subclass — every member stubbed out — is the `TestRpcManager` in [ServerValidationTest.kt](../server/src/test/kotlin/dev/dertyp/ServerValidationTest.kt).

## Logging in

```kotlin
val auth = manager.getAuthService().authenticate("alice", "hunter2")
```

`getAuthService()` returns an `IAuthService` bound to the public `/rpc/auth` endpoint, so it works before you have a token. Hand the result to `updateAuth` and you are done: the manager picks the token up from `getAuthToken()` on the next call. `IAuthService` also offers `refreshToken(refreshToken)` — which you rarely call yourself, because the manager does — and `createDeviceSession(userAgent)` for provisioning a second device. See [AUTHENTICATION.md](AUTHENTICATION.md) and [RPC_SERVICES.md](RPC_SERVICES.md#devdertypservicesiauthservice).

Two more services live on the public endpoint and need no token: `getServerStatsService()` (`IServerStatsService`, including `health()`) and `getHandshakeService()` (`IHandshakeService`).

## Getting services

```kotlin
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.ISongService

val songs = manager.getService<ISongService>()
val albums = manager.getService<IAlbumService>()
```

`getService<T>()` is non-suspending and cheap: stubs are cached per interface in the manager, and they all sit on one shared `transparentClient`. That client resolves its underlying connection lazily through `getAuthenticatedClient()`, which

1. reuses the cached `/rpc/services` connection while the token is still valid;
2. probes TLS support once (`checkSslSupport()`);
3. refreshes the token if `isTokenExpired()`;
4. connects with `Authorization: Bearer <token>` plus the version and locale headers, retrying a transport failure three times with a growing delay, and verifies the connection with a real `IUserService.me()` call before caching it;
5. on a 401, forces exactly one token refresh and retries; if that still fails, calls `handleAuthFailure` and rethrows.

So the normal client code is just `manager.getService<ISongService>().rankedSearch(...)` — connection setup, refresh and reconnect happen underneath. `clear()` tears the cached connection and all stubs down; call it when the server URL or the user changes.

## Dedicated clients for long-lived streams

Every stub from `getService<T>()` shares one connection. A long-running stream — image loading, a mirror session, anything that would otherwise sit in the same multiplexed socket as your UI traffic — is better off on its own. `getDedicatedClient()` opens a *new* authenticated `/rpc/services` connection each time (same token, same headers, no caching), and wrapping it in a `ReconnectingRpcClient` gives that connection the same retry behaviour the shared one has:

```kotlin
import dev.dertyp.rpc.ReconnectingRpcClient
import dev.dertyp.services.IImageService

private val imageClient = ReconnectingRpcClient(
    delegateProvider = { getDedicatedClient() }
)

fun imageService(): IImageService = getService(imageClient)
```

`getService(client)` is the overload that binds a stub to a client you supply instead of the shared one. Remember to `close()` such clients when the connection parameters change — the first-party client does that in one `clearDedicatedClients()` helper next to its `clear()` calls.

## Flows and resilient observation

Any RPC method returning `Flow<T>` is a server stream. `ReconnectingRpcClient.callServerStreaming` already retries a *transport* failure up to five times, but it still terminates when retries run out — which is the right behaviour for a finite stream and the wrong one for an observation that should live as long as the app.

For those, wrap the subscription in `resilientObservation` ([ResilientFlow.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/rpc/ResilientFlow.kt)): on error or completion it waits for a gate flow to report `true`, then resubscribes, with exponential backoff from 1 s to 30 s that resets after the first value.

```kotlin
import dev.dertyp.data.ClientSettingsChange
import dev.dertyp.rpc.resilientObservation
import dev.dertyp.services.IClientSettingsService
import kotlinx.coroutines.flow.Flow

fun settingsChanges(): Flow<ClientSettingsChange> = resilientObservation(
    gate = manager.isServerReachable,
    factory = { manager.getService<IClientSettingsService>().observeSettings() },
)
```

`isServerReachable` is the natural gate: the manager flips it to `false` on a transport failure and back to `true` when a connection succeeds, so the flow stops retrying while the server is known to be down. Pass `isFatal` if some errors should end the stream instead of restarting it, and `onError` to log. Exceptions thrown by *your* collector are passed through unchanged rather than treated as a stream failure.

## Server validation and the TLS fallback

Before storing a server address the user typed, check it:

```kotlin
val result = manager.validateServer(host = "music.example.org", port = 8080, path = "/", useSsl = true)
if (result.validated) {
    // result.useSsl tells you which scheme actually worked
}
```

`validateServer` tries `wss://` then `ws://` (only `ws://` when `useSsl = false`), opens a throwaway kRPC client against `<base>/rpc`, calls `IServerStatsService.health()` with a 5-second timeout and closes it again. The returned `ServerValidationResult` carries `validated` and the scheme that answered (`useSsl`), so a server behind a plain-HTTP reverse proxy is detected instead of silently failing later.

Separately, `checkSslSupport()` runs once per connection attempt on any `https`/`wss` URL: it issues a plain GET to `<base>/handshake` and only cares whether the failure is TLS-shaped (`isSslException` matches "ssl", "tls", "certificate" or "handshake failed" in the message). A TLS failure on a server where TLS has never been confirmed rewrites the stored URL to plain `ws://` through `setRpcUrl`; if TLS *had* been confirmed before, the demotion is kept to the current run only, exposed as `sessionSslOverride`, so a temporary certificate problem does not permanently downgrade the connection. `resetSslSession()` clears both. Any other error is ignored — this probe is a TLS check, not a reachability check. It is worth knowing why: `/handshake` is a WebSocket route, so a plain GET to it answers `400` on a real server and the probe never decodes a `HandshakeResponse` at all. Read the handshake with `fetchHandshake()` or `GET /handshake/handshake` instead.

## Handshake and versions

```kotlin
val handshake = manager.fetchHandshake()
```

`fetchHandshake()` calls `IHandshakeService.handshake()` on the public endpoint, stores the result and swallows failures by returning `null`. The stored value is also available as the `handshake: StateFlow<HandshakeResponse?>` property, and the derived `uiSchemaVersion: StateFlow<Int>` holds `min(server, UiSchemaVersion.CURRENT)` — the version you may actually render. A [`HandshakeResponse`](MODELS.md#devdertypdatahandshakeresponse) carries `apiVersion`, `uiSchemaVersion`, `secure` and `sslSupported`; what to do with each is in [API_VERSIONING.md](API_VERSIONING.md).

## Locale

Override `uiLocale()` to return an `Accept-Language` value:

```kotlin
override fun uiLocale(): String? = "de-AT, de;q=0.9, en;q=0.5"
```

The manager adds it — together with `X-Api-Version` and `X-Ui-Schema-Version` — to every connection it opens, through its internal `connectionHeaders()`. Because headers are read at WebSocket upgrade time, changing the language means calling `clear()` so the next call reconnects; otherwise server-driven UI text keeps arriving in the old language.

## Error surfaces

| What happened | How it reaches you |
|---|---|
| Wrong username or password | `IllegalArgumentException("Invalid username or password")` from `authenticate`. |
| Token rejected at connect time | The WebSocket upgrade answers 401, surfacing as a `WebSocketException` whose message contains `401`. The manager forces one refresh, retries, and only then calls `handleAuthFailure`. |
| Refresh token rejected | `IllegalArgumentException("Invalid refresh token")` or `"Invalid user"` — recognised by `isRefreshRejected` and treated as a genuine logout rather than an outage. |
| Session expired with no refresh token | The manager throws its own `IllegalStateException("Session expired and no refresh token available")` and calls `handleAuthFailure`. |
| Missing capability or admin right | `UnauthorizedException` on the server, reaching you with the message `User does not have required capability: EDIT` or `User is not an admin`. See [AUTHENTICATION.md](AUTHENTICATION.md). |
| Bad arguments | `IllegalArgumentException` with the server's message. |
| Server unreachable, socket closed, DNS failure, timeout | Classified by `isTransportFailure()` (`IOException`, `ConnectTimeoutException`, `HttpRequestTimeoutException`, `UnresolvedAddressException`, closed-channel exceptions, and a cancelled `RpcClient`). Retried by `ReconnectingRpcClient`, then rethrown with `isServerReachable` set to `false`. An unresolvable address is never retried. |

Treat the exception *message* as the reliable part; match on types only for the ones listed above. `CancellationException` is always rethrown untouched, so structured concurrency keeps working.

## Coroutine scope and dispatchers

`BaseRpcServiceManager(client, baseScope)` defaults its scope to `CoroutineScope(ioDispatcher + SupervisorJob())` and wraps it in a `CoroutineExceptionHandler` that maps `IOException`, `ConnectTimeoutException` and `UnresolvedAddressException` to `onServerUnreachable()`. Pass your own `baseScope` if the manager should die with a component of your app.

All connection work runs on `ioDispatcher`, and the RPC clients themselves are created inside the manager's scope, so cancelling that scope also tears down the sockets. None of the manager's public methods are safe to call from a `runBlocking` on a UI thread — `getAuthenticatedClient()` can block on a network round trip.

## Testing against the mock server

```bash
docker run -p 8081:8081 ghcr.io/dertyp7214/synara-mock:latest-dev
```

Point `getRpcUrl()` at `ws://localhost:8081`. The mock server registers every service in the registry at `/rpc`, `/rpc/auth` and `/rpc/services` and generates dummy data from the return types, so you can build screens before a real library exists. Its only authentication rule is that protected services need *some* `Authorization` header — the content is never checked, so your token handling compiles but is not exercised. Details in [MOCK_SERVER.md](MOCK_SERVER.md).

For unit tests, the pattern in [ServerValidationTest.kt](../server/src/test/kotlin/dev/dertyp/ServerValidationTest.kt) works without any server: a Ktor `testApplication` registering one service with `installKrpc { serialization { cbor(AppCbor) } }` on the client side, and a stub manager whose members all return constants.

## Checklist

1. Add `common-rpc` as a submodule; include it and both KSP processor projects in `settings.gradle.kts`; depend on `project(":common-rpc")`.
2. Build the `HttpClient` once with `createRpcHttpClient(appVersion)`.
3. Subclass `BaseRpcServiceManager`: URL storage, token storage, an `isTokenExpired()` with a small margin, and a `handleAuthFailure` that logs the user out.
4. Override `uiLocale()` if you render server-driven UI, and `supportsSsl` if your platform cannot do TLS.
5. Validate a user-entered address with `validateServer` before storing it; store the scheme it returns.
6. Call `fetchHandshake()` after the first successful connection and compare `apiVersion` with yours.
7. Use `getService<T>()` for ordinary calls, a `ReconnectingRpcClient` over `getDedicatedClient()` for long-lived streams.
8. Wrap never-ending observations in `resilientObservation(gate = isServerReachable)`.
9. Call `clear()` whenever the server URL, the locale or the user changes.
10. Drive the `isServerReachable` flow into your UI so the user sees an outage instead of a frozen screen.

## Next

- [AUTHENTICATION.md](AUTHENTICATION.md) — tokens, sessions, capabilities and API keys.
- [API_VERSIONING.md](API_VERSIONING.md) — what `X-Api-Version` changes and how to pick one.
- [RPC_SERVICES.md](RPC_SERVICES.md) and [MODELS.md](MODELS.md) — the generated method and model reference.
- [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md) — playing and downloading audio.
- [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md) — rendering server-described screens.
- [CLIENT_SETTINGS.md](CLIENT_SETTINGS.md) — storing your client's settings on the server.
- [CLIENT_REST.md](CLIENT_REST.md) — the same API over HTTP, for the parts of your stack that are not Kotlin.
