# Deprecations

This page is a checklist of what has to change in the code when a version bump reaches a certain version. When you deprecate something, add an entry under the version that will remove it, or under "No target version yet" if that is not decided. When you bump a version, work through its section and delete the entries you completed. It is organised by version kind, then by target version. See [API_VERSIONING.md](API_VERSIONING.md) for how the versions work and [API_CONSTANTS.md](API_CONSTANTS.md) for the current values.

## API version

The API version is `ApiVersion.CURRENT` ([ApiVersion.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/data/ApiVersion.kt)). Features are gated by `ClientFeature` ([ClientInfo.kt](../server/src/main/kotlin/dev/dertyp/core/ClientInfo.kt)) and implemented as `CompatRule`s ([CompatRule.kt](../server/src/main/kotlin/dev/dertyp/utils/CompatRule.kt)) that [ClientCompat.kt](../server/src/main/kotlin/dev/dertyp/utils/ClientCompat.kt) applies to responses. A compat rule can only go once every client that needs it is unsupported.

### API version 9

Everything below is marked with the constant `REMOVED_IN_API_9` ([Compatibility.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/rpc/annotations/Compatibility.kt)) in its `@Deprecated` message, or with `@LegacyWireName`. Grep for both to find the current usages.

Removing RPC methods or wire keys breaks clients built against version 8 or older. kRPC routes a call by the fully qualified interface name plus the method name, so a plugin cannot serve removed methods. Decide how old clients are handled as part of that bump.

User model and mirroring:

- Remove `User.passwordHash` ([User.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/data/User.kt)). It is always `""` today. Older clients require the key, so this needs those clients to be unsupported. Also drop the `copy(passwordHash = ...)` in `MirrorService.getUsers` ([MirrorService.kt](../server/src/main/kotlin/dev/dertyp/services/MirrorService.kt)).
- `IMirrorService.getUsers` still sends the hash for older mirror servers. At 9 it stops sending it and mirrors use `getUserPasswordHashes` ([IMirrorService.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/services/IMirrorService.kt)). Update `RemoteMirrorService` to combine the two calls where it needs hashes.

Renamed methods. The old names are `@Deprecated` and have a default body in the common-rpc interface that delegates to the new name, so neither the server nor client wrappers override them. kRPC still serves them to old clients, because its `RpcDeclarationScanner` registers every declared function of an `@Rpc` interface, abstract or not. Delete the old methods from the common-rpc interfaces:

| Interface | Old name | New name |
|---|---|---|
| `IUserService` | `findUserById` | `byId` |
| `IUserService` | `findUserByUsername` | `byUsername` |
| `IUserService` | `getAllUsers` | `allUsers` |
| `IImportService` | `getAllImportServices` | `allImportServices` |
| `IMetadataService` | `getAllMetadataTypes` | `allMetadataTypes` |
| `IArtistService` | `artistsWithoutMusicBrainzIdFlow` | `artistsWithoutMusicBrainzId` |
| `IListenBackupService` | `getStateFlow` | `observeState` |
| `IScheduledTaskConfigurationService` | `getConfigurationsFlow` | `observeConfigurations` |
| `IScheduledTaskLogService` | `getGroupedLogsFlow` | `observeGroupedLogs` |
| `IRadioService` | `radioFlow` | `observeRadio` |

The server implements only the new names, in `UserService`, `ImportService`, `MetadataService`, `MetadataDispatcherService` (a second delegate for `allMetadataTypes`), `ArtistService`, `ListenBackupService`, `ScheduledTaskConfigurationService`, `ScheduledTaskLogService` and `RadioRpcService`, all under `server/src/main/kotlin/dev/dertyp/services/`. Nothing changes there when the old names go.

Deprecated without a rename. The server cannot express these through the replacement calls alone. They have a default body in the common-rpc interface, so client wrappers never override them. The flows emit the matching getter once and `setLiked` delegates to `setLikeLevel`. The server keeps its live overrides for older clients until API version 9. Its overrides are `@Deprecated` with the same message. Delete them from the interface and from the server:

- `ISongService.setLiked`: use `setLikeLevel`. Implemented in `SongRpcService` ([SongService.kt](../server/src/main/kotlin/dev/dertyp/services/SongService.kt)).
- `IListenBrainzService.getStatusFlow`, `IScrobbleService.recentListensFlow`, `recentArtistsFlow`, `recentAlbumsFlow` and `IUiService.getHomeCardsFlow`: all replaced by `IChangeService.observeChanges` plus the matching getter (`getStatus`, `recentListens`, `recentArtists`, `recentAlbums`, `getHomeCards`). Implemented in `sync/ListenBrainzService.kt`, `RpcScrobbleService` in `ScrobbleService.kt` and `ui/RpcUiService.kt`. The server-side flows behind them (`statusFlow`, `recentListensFlow` and the others on the services, `homeLayoutFlow`) can go too unless something else still uses them.

`@RestExclude`:

- The three old methods `getAllUsers`, `getAllImportServices` and `getAllMetadataTypes` carry `@RestExclude` because their replacement derives the same REST route. Remove the annotation usage together with the methods.

Generated-code warnings:

- Once the deprecated RPC methods are removed, also remove `"DEPRECATION"` from the file-level suppress lists the generators write: the `@file:Suppress` line of `NativeDispatchers.kt` in [RpcProcessor.kt](../common-rpc/compiler/src/main/kotlin/dev/dertyp/rpc/compiler/RpcProcessor.kt) and the `Suppress` file annotation of the `*Rest.kt` files in [KotlinEmitter.kt](../common-rpc/rest-compiler/src/main/kotlin/dev/dertyp/rpc/rest/KotlinEmitter.kt).

Field renames (`ClientFeature.FIELD_RENAMES`, introduced with version 8). common-rpc already uses the new names. Clients below 8 get the old names from the server's wire layer only:

| Property | Old name on the wire |
|---|---|
| `Album.musicBrainzId`, `Artist.musicBrainzId`, `ArtistCredit.musicBrainzId` | `musicbrainzId` |
| `PlaybackReport.isPlaying` | `playing` |
| `CollectionSongMatch.directMember`, `RadioChannelSongMatch.directMember` | `explicitMember` |
| `isShuffled` on `PlaybackState`, `QueueInfo`, `QueueMeta` and `RemotePlaybackStatus` | `shuffleMode` |
| `QueueItem.userAdded` | `explicit` |
| `PlaybackState.QueueEntry.WithSong` (the sealed subclass, its serial name) | `Explicit` |

- Delete the package `server/src/main/kotlin/dev/dertyp/core/wire/` (`LegacyWire`, `LegacyCborFormat`, `WireJson`) and its tests in `server/src/test/kotlin/dev/dertyp/core/wire/`, including the old-client model copies and `LegacyWireRegistryTest`.
- Remove the legacy formats from [WireFormats.kt](../server/src/main/kotlin/dev/dertyp/core/WireFormats.kt): `ServerWire` with its sealed registry, `LegacyServerJson`, `LegacyServerCbor`, `jsonFor` and `cborFor`. The kRPC routes in [Routing.kt](../server/src/main/kotlin/dev/dertyp/Routing.kt) and the proxy servers in [ReverseProxyService.kt](../server/src/main/kotlin/dev/dertyp/services/ReverseProxyService.kt) go back to `cbor(AppCbor)`, and [RestCall.kt](../server/src/main/kotlin/dev/dertyp/routing/rest/RestCall.kt) goes back to `AppJson` everywhere. Reduce `WireFormatKrpcTest` and the version tests in `GeneratedRestRoutesSmokeTest` to the new names.
- Remove `ClientFeature.FIELD_RENAMES` with its fallback and its `ClientInfoTest` cases, then regenerate [API_CONSTANTS.md](API_CONSTANTS.md).
- Remove every `@LegacyWireName` usage, the annotation itself in [Compatibility.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/rpc/annotations/Compatibility.kt) and the old-name rows it produces in `DocProcessor.kt` of the doc-compiler, then regenerate the docs.

Legacy flat audio fields:

- Remove `sampleRate`, `bitsPerSample`, `bitRate`, `fileSize` and `atmosPath` from `Song`, `UserSong` and the base class in [Song.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/data/Song.kt), all `@Deprecated(LEGACY_AUDIO_FIELDS)`, together with the `LEGACY_AUDIO_FIELDS` constant, whose message builds on `REMOVED_IN_API_9`. They exist for clients below `ClientFeature.AUDIO_INFO` (API version 4) and are filled only by `AudioInfoCompat` and `DolbyAtmosCompat`. Remove `BaseSong.effectiveAudio`, the two rules and their `CompatRules.all` entries with them.

### No target version yet

- The remaining compat rules exist only for old clients. `TitleTagsCompat` (version 6) and `ReleaseVersionsCompat` (version 7) and `AlbumTitleTagsCompat` (version 9) go with their `ClientFeature` entries once clients below those versions are unsupported. The same holds for `DOLBY_ATMOS` (3) and `LOSSLESS_WAV_AIFF` (2), which have no `CompatRule` and are gated inline by `ClientInfo.supports`. Removing a feature also means removing its row in [API_CONSTANTS.md](API_CONSTANTS.md) by regeneration.
- Raising `ApiVersion.LEGACY` (currently 1) is what makes a rule dead. Update `ClientInfo.fromHeaders` expectations and the tests when you do.

## UI schema version

The UI schema version is `UiSchemaVersion.CURRENT` ([UiSchemaVersion.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/ui/UiSchemaVersion.kt)). Each component is registered with its introducing version in `UiSchema.introducedIn` and `UiSchemaCompat` downgrades newer ones to `Fallback` or, for `FileField`, to a base64 `TextField` ([UiSchemaCompat.kt](../server/src/main/kotlin/dev/dertyp/utils/UiSchemaCompat.kt)). See [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md).

### No target version yet

- `UiComponent.FileField` (introduced in schema version 2): the `asTextField` downgrade in `UiSchemaCompat` and `BASE64_HINT` can go once clients below schema version 2 are unsupported. When schema version 1 is dropped, `UiSchemaCompat` itself and the `introducedIn` map can be reduced the same way.

## Plugin API version

The plugin API version is `ISynaraPlugin.apiVersion` ([ISynaraPlugin.kt](../plugin-api/src/main/kotlin/dev/dertyp/plugins/ISynaraPlugin.kt), default 1). The server accepts plugins up to `PluginManager.CURRENT_API_VERSION` (currently 3) and the history is in [PLUGINS.md](PLUGINS.md). Nothing in `plugin-api` is annotated `@Deprecated` today.

### No target version yet

- Nothing is deprecated. When you drop support for old plugins, raise the lowest accepted version in `PluginManager` and remove the `PluginContext` members only older versions needed.

## Other versioned things

### No target version yet

- Audio timeline format: `AudioTimelineCodec.VERSION` (currently 3) is stored in `SongAudioTimelineTable.version` and rows with a lower value are re-analysed by `AudioAnalysisService`. A format change bumps the constant. Nothing is deprecated.
- Credential protocol: `CredentialProtocol.PROTOCOL_VERSION` (currently 1, in `common-credentials`) is reported by the credential server health endpoint as `protocolVersion`. See [CREDENTIAL_SERVER.md](CREDENTIAL_SERVER.md). Nothing is deprecated.
- `ReverseProxyService` carries two `@Deprecated(level = ERROR)` overrides (`host` and `port`) of Ktor's `RequestConnectionPoint`. They belong to the Ktor interface and are not Synara deprecations, so there is nothing to remove.

## Conventions

- Mark deprecated API with a constant per target version: `@Deprecated(REMOVED_IN_API_9 + " Use x.", ReplaceWith("x()"))`. Leave out `ReplaceWith` when the replacement is not a drop-in call. Add a new `REMOVED_IN_API_N` constant next to the existing one when you target another version.
- A renamed method keeps its old name as a `@Deprecated` method with a default body in the common-rpc interface that delegates to the new name. Servers and client wrappers implement only the new name. kRPC keeps serving the old name because `RpcDeclarationScanner` registers every declared function.
- Every deprecated RPC method gets a default body in the common-rpc interface, so clients never override it. A deprecation without a rename delegates to its replacement as closely as it can, for example a one-shot flow over the matching getter. The server keeps its own override where older clients need the full behaviour until removal and marks it `@Deprecated` with the same message, so the override itself raises no warning.
- The generated REST handlers (`server/build/generated/ksp/.../*Rest.kt`) and the generated `NativeDispatchers.kt` of common-rpc still call every deprecated method. Their generators write a file-level `Suppress("DEPRECATION")` into those files, so the generated code raises no deprecation warnings. This is the only place where warnings are suppressed. Hand-written code never uses `@Suppress` and keeps its deprecation warnings. The generated `Defaults` objects of the REST layer copy the `@Deprecated` annotation onto their probe overrides.
- Wire renames switch to the new name immediately in common-rpc. Only the server keeps the old name, for older clients, through `@LegacyWireName` on the renamed property or class. Clients never carry compatibility code and use the plain generated serializers.
- A renamed field that is reachable through a sealed class needs that sealed class with all its subclasses in the `ServerWire` registry ([WireFormats.kt](../server/src/main/kotlin/dev/dertyp/core/WireFormats.kt)). `LegacyWireRegistryTest` fails when one is missing.
- Parameters are never renamed, because REST derives its keys from them.
- Old methods whose replacement derives the same REST route get `@RestExclude`.
- Every new deprecation gets an entry in this file.
