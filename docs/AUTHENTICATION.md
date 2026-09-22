# Authentication

This page is for client developers who need to log a user in and keep them logged in. It covers the login calls on both the RPC and the REST surface, what the access token contains and how long it lives, how refreshing works, how sessions map to devices and what logging out really does, device sessions for TVs and other second screens, when a cookie is needed instead of an `Authorization` header, how admin rights and capabilities gate individual methods, API keys for non-interactive clients, and what every failure looks like on the wire. The generated reference for the services mentioned here is [RPC_SERVICES.md](RPC_SERVICES.md); the REST routes are in [REST_API.md](REST_API.md).

There is no self-registration. The first account exists because the server seeds one on startup: if the `client.id` and `client.secret` configuration properties are set — the `CLIENT_ID` and `CLIENT_SECRET` environment variables, listed in [ENVIRONMENT_VARIABLES.md](ENVIRONMENT_VARIABLES.md) as the initial admin username and password — an admin user with that username and a BCrypt hash of that password is inserted with `insertIgnore` ([DatabaseManager.kt](../server/src/main/kotlin/dev/dertyp/services/DatabaseManager.kt)). Because the insert is ignored when the username already exists, this happens once on the first run and never overwrites the row afterwards, so changing the variables later does not reset the password. Every further account is created by that admin through `POST /register` (see below) or `IUserService.createUser`.

## Logging in

All three forms return the same [`AuthenticationResponse`](MODELS.md#devdertypdataauthenticationresponse): `token`, `refreshToken`, and `expiresAt` as epoch milliseconds.

**RPC** — on the public `/rpc/auth` endpoint, so no token is needed to make the call:

```kotlin
val auth = manager.getAuthService().authenticate("alice", "hunter2")
```

**REST, hand-written route** — takes a JSON body, and is the one a browser front-end uses:

```bash
curl -X POST http://localhost:8080/authenticate \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"hunter2"}'
```

**REST, generated route** — the same `IAuthService.authenticate` method exposed like every other service method, with its parameters as query parameters:

```bash
curl -X POST 'http://localhost:8080/auth/authenticate?username=alice&password=hunter2'
```

Prefer `POST /authenticate` in anything that could end up in a server log or a browser history: the generated route carries the password in the URL.

Both paths verify the password with BCrypt, create a session row recording the request's `User-Agent` and remote address, and sign a token bound to that session. Whatever user agent you send is what the user sees in their session list, so send something recognisable.

## The token

The access token is a JWT signed with HMAC-256 using the server's `jwt.secret`, with the `jwt.audience` and `jwt.issuer` from the server configuration ([JwtService.kt](../server/src/main/kotlin/dev/dertyp/services/JwtService.kt)). The claim names and both tokens' lifetimes are in [API_CONSTANTS.md#authentication](API_CONSTANTS.md#authentication).

Validation is not purely cryptographic: after verifying the signature, audience and issuer, the server looks the `ses` claim up and rejects the token if that session is no longer active. A still-valid, unexpired token therefore stops working the moment its session is deactivated.

The refresh token is not a JWT — it is a fixed number of random bytes, URL-safe Base64, truncated to a fixed length, stored server-side; see [API_CONSTANTS.md#authentication](API_CONSTANTS.md#authentication) for the exact sizes and its lifetime.

Clients should treat `expiresAt` as the source of truth and refresh slightly early — the first-party client considers a token expired one minute before `expiresAt` — rather than waiting for a call to fail.

## Refreshing

A refresh exchanges the refresh token for a complete new pair — a new access token *and* a new refresh token — while keeping the same session:

```kotlin
val auth = manager.getAuthService().refreshToken(oldRefreshToken)
```

```bash
curl -X POST http://localhost:8080/refresh-token \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"…"}'
```

The generated form is `POST /auth/refreshToken?refreshToken=…`.

Store the whole response each time; dropping the new refresh token and reusing the old one is the most common way clients end up logged out. `BaseRpcServiceManager` does all of this for you — it refreshes before connecting whenever `isTokenExpired()` says so, and forces exactly one refresh when a connection is rejected with 401. See [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md).

## Sessions and logging out

Every login creates a session row holding the user agent, the IP address and a last-active timestamp that is bumped on each authenticated request.

| Action | RPC | REST |
|---|---|---|
| List the user's sessions | `ISessionService.getSessions()` | `GET /sessions`, or the generated `GET /session/sessions` |
| End one session | `ISessionService.deactivateSession(sessionId)` | `DELETE /sessions/{sessionId}`, or the generated `DELETE /session/deactivateSession/{sessionId}` |

Deactivating a session is what "log out" means on the server side: because token validation checks the session, every token carrying that `ses` claim — on every device sharing it — stops working immediately, without waiting for `exp`. A client can only deactivate sessions belonging to its own user. Locally, logging out is also discarding the stored token and refresh token; `BaseRpcServiceManager` drives that through `handleAuthFailure`.

Sessions inactive past a fixed threshold, and sessions that were deactivated, are cleaned up by a background task.

## Device sessions

A TV, a car head unit or a second screen often cannot show a keyboard. `IAuthService.createDeviceSession(userAgent)` lets an already-authenticated client mint a *second, independent* session for the same user and hand its tokens to the other device:

```kotlin
val deviceAuth = manager.getAuthService().createDeviceSession("Synara TV (Apple TV)")
```

The new session is a normal session with its own id and its own token pair, so the user sees it separately in the session list and can revoke it alone without touching the phone that created it. The generated REST form is `POST /auth/deviceSession?userAgent=…`.

`IAuthService` lives on the *public* endpoint, where the JWT plugin does not populate a principal, so this method resolves the caller from the request itself: first the auth cookie, then a `Bearer` token in the `Authorization` header. Send one of them or the call fails with `Not authenticated.`

## Bearer header or cookie

The server accepts the access token two ways, and prefers the cookie when both are present:

1. `Authorization: Bearer <token>` — use this everywhere you control the request.
2. The cookie named in [API_CONSTANTS.md#authentication](API_CONSTANTS.md#authentication) (`synara-auth` in the code samples throughout these docs), holding the raw token.

The cookie exists for contexts that cannot set headers: `<img>`, `<audio>` and `<video>` sources, `EventSource` (Server-Sent Events), and plain navigation. The web UI the server ships sets it (and a `synara-refresh` cookie for its own refresh flow) from the login response with `path=/; SameSite=Strict`, expiring at `expiresAt`, and clears both on logout ([mirror.js](../server/src/main/resources/static/mirror.js)). A native client should stay on the header.

Two path families skip JWT authentication entirely: anything ending in `/callback` (OAuth returns) and anything containing `/proxy/`.

## Admin and capabilities

Authorization has two levels. `isAdmin` is a flag on the user; capabilities are a list. **An admin implicitly has every capability** — `hasCapability` returns true for an admin regardless of the list ([User.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/data/User.kt)). The current user's own flags come from `IUserService.me()` (`GET /user/me`) or `GET /userInfo`; use them to hide actions the user cannot perform, and still handle the failure, because the server checks independently. The two return different models: `me()` answers the full [`User`](MODELS.md#devdertypdatauser) — including a `passwordHash` field that the server blanks to `""` before sending — while `GET /userInfo` answers [`UserInfo`](MODELS.md#devdertypdatauserinfo), the same identity without that field.

Methods are gated by the `@RequiresCapability` and `@RequiresAdmin` annotations on the service interfaces, enforced by a proxy in front of every authenticated service ([Authorization.kt](../server/src/main/kotlin/dev/dertyp/utils/Authorization.kt)). The full list of which service methods each capability and admin-only status gates is generated from those annotations into [PERMISSIONS.md](PERMISSIONS.md).

Everything not listed there needs nothing beyond a valid session. `POST /register` is admin-only too, checked in the route itself rather than by annotation.

## API keys

Interactive clients use tokens; long-lived and headless integrations use API keys, which never expire on their own and are bound to a scope ([IApiKeyService](RPC_SERVICES.md#devdertypservicesiapikeyservice)):

```kotlin
val key = manager.getService<IApiKeyService>().createApiKey("Living room speaker", listOf("radio"))
```

The raw key is returned once at creation (and can be read back later with `getApiKeyString(id)`); it looks like `synara_<base64>`. `listApiKeys()` returns metadata only — label, creation time, last use, scopes, revocation state — never the secret. `revokeApiKey(id)` kills a key permanently. The built-in scopes are listed in [API_CONSTANTS.md#authentication](API_CONSTANTS.md#authentication), but plugins can register more, so `listAvailableScopes()` is the authoritative list for a given server.

Where a route takes an API key, it is read from `?apiKey=`, the `X-API-Key` header or an `Authorization: Bearer` header, in that order ([Call.kt](../server/src/main/kotlin/dev/dertyp/core/Call.kt)). A key that is revoked, expired or missing the required scope is treated as no key at all, and the route answers 401. API keys do **not** unlock the RPC or the general REST surface — those still need a JWT.

## What failures look like

Over **REST** ([RestCall.kt](../server/src/main/kotlin/dev/dertyp/routing/rest/RestCall.kt)), errors are plain-text bodies with a status code:

| Status | When |
|---|---|
| 400 | An `IllegalArgumentException` from the service, or unbindable parameters. The body is the exception message. This is how the **generated** auth routes report bad credentials: `POST /auth/authenticate?username=…&password=…` with a wrong password answers `400` with the body `Invalid username or password`, and the generated refresh route answers `400 Invalid refresh token` — only the hand-written `POST /authenticate` and `POST /refresh-token` use 401. A client on the generated routes must therefore treat 400 as "credentials rejected" and not wait for a 401. |
| 401 | No valid token: the JWT challenge answers `Token is not valid or has expired`; an authenticated route with no resolvable user answers an empty 401; `POST /authenticate` answers `Invalid username or password` and `POST /refresh-token` answers `Invalid refresh token` or `Invalid user`. |
| 403 | An `UnauthorizedException` — the capability or admin check failed. Body: `User does not have required capability: EDIT` or `User is not an admin`. `POST /register` by a non-admin answers `Only admins can register new users`. |
| 404 | The method returned `null` (no such song, album, …). |
| 409 | `POST /register` for a username that already exists. |
| 500 | Anything else, with the exception message in the body. |

Over **RPC**, the same conditions surface as exceptions on the calling coroutine, carrying the server's message:

- A rejected token fails the WebSocket upgrade with 401, which arrives as a `WebSocketException` whose message contains `401` — this is what `BaseRpcServiceManager.isAuthException` matches.
- A failed capability or admin check throws `UnauthorizedException` with the messages above.
- Bad credentials or a bad refresh token throw `IllegalArgumentException` (`Invalid username or password`, `Invalid refresh token`, `Invalid user`).

Match on the message rather than the exact exception class where you can; that is what the SDK itself does.

## The lifecycle

```mermaid
sequenceDiagram
    participant C as Client
    participant A as /rpc/auth (public)
    participant S as /rpc/services (JWT)

    C->>A: authenticate(username, password)
    A-->>C: token, refreshToken, expiresAt
    Note over C: store all three; session created server-side

    C->>S: connect with Bearer token
    S-->>C: ok, calls flow

    Note over C: now >= expiresAt - margin
    C->>A: refreshToken(refreshToken)
    A-->>C: new token, new refreshToken, new expiresAt
    C->>S: reconnect with the new token

    Note over C,S: session revoked elsewhere, or token rejected
    S-->>C: 401 on the upgrade
    C->>A: refreshToken(refreshToken) (one forced retry)
    A-->>C: IllegalArgumentException("Invalid refresh token")
    Note over C: handleAuthFailure: drop credentials, show login
    C->>A: authenticate(username, password)
```

## Next

- [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md) — the manager that implements this loop for you.
- [CLIENT_REST.md](CLIENT_REST.md) — sending the token over HTTP, including SSE streams.
- [API_VERSIONING.md](API_VERSIONING.md) — the other header every request should carry.
- [CLIENT_GETTING_STARTED.md](CLIENT_GETTING_STARTED.md) — the surfaces and conventions overview.
- [RPC_SERVICES.md](RPC_SERVICES.md#devdertypservicesiauthservice) — `IAuthService`, `ISessionService`, `IUserService` and `IApiKeyService` signatures.
