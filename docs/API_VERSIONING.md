# API Versioning

This page is for client developers who need to know which version number to send, what the server does differently when it sees an older one, and how to tell an old server from a new one. It covers the `X-Api-Version` header and the features it gates, the separate UI schema version, the handshake that reports both, and why the `common-rpc` submodule commit — not a release tag — is the unit of compatibility. Everything here applies to the RPC and the REST surface alike, because both are shaped by the same code.

## Why the header exists

Synara has no versioned URLs. There is one set of services, and old clients keep working because the server *shapes its responses* to what the client says it understands. That statement is the `X-Api-Version` header ([ApiVersion.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/data/ApiVersion.kt)); the header name and the `LEGACY`/`CURRENT` values are in [API_CONSTANTS.md#api-version](API_CONSTANTS.md#api-version).

Send it on every request — including the WebSocket upgrade that opens an RPC connection, where headers are read once at upgrade time. A missing header, a non-numeric one, or anything below `ApiVersion.LEGACY` is read as `ApiVersion.LEGACY` ([ClientInfo.kt](../server/src/main/kotlin/dev/dertyp/core/ClientInfo.kt)), which is the most conservative shape the server can produce — and the one that hides the newest half of the API from you.

Kotlin clients get this for free: `createRpcHttpClient` installs it as a default request header, and `BaseRpcServiceManager` adds it explicitly to every connection it opens. See [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md).

## Features

Each version introduced one feature. A client "supports" a feature when the version it sent is at least the feature's minimum, and for every feature the server has a rule describing what to do when it does not. The full table — which version introduced which feature, what it introduces and what an older client gets instead — is in [API_CONSTANTS.md#features](API_CONSTANTS.md#features).

The deprecated song fields are only ever populated by this shaping — a current-version client always reads `audio` / `atmos` and never the flat fields ([Song.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/data/Song.kt)).

## How the shaping happens

Every registered service is wrapped in a chain of proxies — caching, then metrics, then client compatibility on the outside ([RpcRegistry.kt](../server/src/main/kotlin/dev/dertyp/routing/RpcRegistry.kt)); the REST layer wraps each service the same way. Because shaping sits *outside* the cache, one cached value is reshaped per connection instead of being cached per version.

The shaper ([ClientCompat.kt](../server/src/main/kotlin/dev/dertyp/utils/ClientCompat.kt)) walks the returned value recursively, so a rule reaches a `Song` whether it came back on its own, inside a `PaginatedResponse`, a `List`, a `Map`, a `Flow`, a queue entry, a now-playing record or a listen history entry. Rules are applied newest-first, so a client sending `ApiVersion.LEGACY` gets the title-tag rule, then the audio-info rule, then the Atmos rule, each on the result of the previous. When the client supports everything, the shaper reports itself as a no-op and no proxy is installed at all.

The consequence for you: **you never see a half-shaped object.** Whatever version you claim, the models are internally consistent; you just have to claim the right one.

## The UI schema version

Server-driven UI evolves faster than the API itself, so it has its own header ([UiSchemaVersion.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/ui/UiSchemaVersion.kt)); the header name and the `NONE`/`CURRENT` values are in [API_CONSTANTS.md#ui-schema-version](API_CONSTANTS.md#ui-schema-version).

Components carry the schema version they were introduced in; a component newer than the version you sent is replaced by a `Fallback` node, and the enclosing tree is rebuilt around it so the rest still renders ([UiSchemaCompat.kt](../server/src/main/kotlin/dev/dertyp/utils/UiSchemaCompat.kt)). Omitting the header means version 0, which turns *every* component into `Fallback`. New components and enum entries are only ever added together with a bump of `CURRENT`, so a client that sends its own `CURRENT` never meets a type it does not know. The full vocabulary is in [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md).

`Accept-Language` belongs to the same family of connection headers: all server-driven UI text arrives already translated, highest-quality language wins, and the fallback is `en`.

## The handshake

`IHandshakeService.handshake()` — REST `GET /handshake/handshake`, and also a one-shot CBOR frame on the `/handshake` WebSocket — answers with a [`HandshakeResponse`](MODELS.md#devdertypdatahandshakeresponse) ([HandshakeService.kt](../server/src/main/kotlin/dev/dertyp/services/HandshakeService.kt)):

| Field | Meaning |
|---|---|
| `apiVersion` | The highest API version **this server** supports (`ApiVersion.CURRENT` of the build it runs). |
| `uiSchemaVersion` | The highest UI schema version it supports; `0` means it has no server-driven UI. |
| `secure` | Whether *this* connection is HTTPS/WSS, including via an `X-Forwarded-Proto` header from a reverse proxy. |
| `sslSupported` | Whether the server can serve TLS at all — true when the connection is already secure, or when the server is configured as TLS-capable. Use it to decide whether offering an HTTPS toggle makes sense. |

It needs no authentication, which makes it the right first call after the user enters a server address.

## Choosing the version you send

Send the `ApiVersion.CURRENT` of the `common-rpc` you compiled against, and nothing else. Do not send a number you invented, do not send a higher one to "get more fields" — the extra fields are real, and your models will not have them.

Then compare the handshake with your own constant:

- `handshake.apiVersion >= ApiVersion.CURRENT` — the normal case. The server understands everything you do.
- `handshake.apiVersion < ApiVersion.CURRENT` — **the server is older than your client.** No shaping protects you here: the server simply never had the newer fields, so `audio`/`atmos` may be absent on a version-3 server, `tags` on a version-5 server, and the Atmos stream will not exist. Fall back the way the server would have: `BaseSong.effectiveAudio` in `common-rpc` reconstructs an `AudioInfo` from the deprecated flat fields for exactly this case, and an empty `tags` list is indistinguishable from a song without markers.
- `handshake.uiSchemaVersion == 0` — do not show server-driven entry points. `BaseRpcServiceManager` already exposes `min(server, CURRENT)` as its `uiSchemaVersion` flow, which is the number your renderer should honour.

Treat both numbers as data to branch on, never as a reason to refuse to connect.

## The compatibility unit is the submodule commit

There is no separately published SDK version. `common-rpc` is a git submodule of both the server repository and every client repository, pointing at the same upstream, and the compatibility unit is **the commit each repository's pointer references**. When an interface, a model or a version constant changes, the change is committed in the submodule and the pointer is bumped in each repository that needs it — the server repository's `.gitmodules` names the same GitHub repository a client would add.

In practice:

- A client built against a newer submodule commit than the server runs can call methods the server does not implement. Compare the handshake before using anything version-gated.
- A client built against an older commit is the case the whole shaping machinery exists for, and is safe.
- Bumping the pointer in your client is how you adopt a new API version — along with the new `ApiVersion.CURRENT` that comes with it.
- The generated references in [RPC_SERVICES.md](RPC_SERVICES.md) and [MODELS.md](MODELS.md) are produced from the submodule by KSP, so they always describe exactly the commit the repository points at.

## Next

- [CLIENT_KOTLIN_RPC.md](CLIENT_KOTLIN_RPC.md) — where the headers and the handshake are wired up for you.
- [CLIENT_REST.md](CLIENT_REST.md) — sending the same headers over HTTP.
- [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md) — the UI schema in full.
- [STREAMING_AND_PLAYBACK.md](STREAMING_AND_PLAYBACK.md) — what versions 2 and 3 actually change about playback.
- [CLIENT_GETTING_STARTED.md](CLIENT_GETTING_STARTED.md) — the surfaces and conventions overview.
