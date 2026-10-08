# Deprecations

This page is a checklist of what has to change in the code when a version bump reaches a certain version. When you deprecate something, add an entry under the version that will remove it, or under "No target version yet" if that is not decided. When you bump a version, work through its section and delete the entries you completed. It is organised by version kind, then by target version. See [API_VERSIONING.md](API_VERSIONING.md) for how the versions work and [API_CONSTANTS.md](API_CONSTANTS.md) for the current values.

## API version

The API version is `ApiVersion.CURRENT` ([ApiVersion.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/data/ApiVersion.kt)). Features are gated by `ClientFeature` ([ClientInfo.kt](../server/src/main/kotlin/dev/dertyp/core/ClientInfo.kt)) and implemented as `CompatRule`s ([CompatRule.kt](../server/src/main/kotlin/dev/dertyp/utils/CompatRule.kt)) that [ClientCompat.kt](../server/src/main/kotlin/dev/dertyp/utils/ClientCompat.kt) applies to responses. A compat rule can only go once every client that needs it is unsupported. Nothing is deprecated right now, so no version has a section of its own.

### No target version yet

- The remaining compat rules exist only for old clients. `TitleTagsCompat` (version 6), `ReleaseVersionsCompat` (version 7), `AlbumTitleTagsCompat` (version 9) and `AlbumVersionsCompat` (version 9) can go once clients below those versions are unsupported. The same holds for the inline `ClientInfo.supports` checks of `DOLBY_ATMOS` (3) and `LOSSLESS_WAV_AIFF` (2), which have no `CompatRule`.
- `ClientFeature` entries are never removed. They are the history of what each API version introduced and they produce the feature table in [API_CONSTANTS.md](API_CONSTANTS.md). When the compat code of a feature is removed, rewrite its `fallback` text to say that there is no fallback, then regenerate the table. `AUDIO_INFO` (4) and `FIELD_RENAMES` (8) are in that state today. `AUDIO_INFO` has no code reference left and exists for documentation only.
- `FIELD_RENAMES` still gates the legacy wire formats in `jsonFor` and `cborFor` ([WireFormats.kt](../server/src/main/kotlin/dev/dertyp/core/WireFormats.kt)). No model carries `@LegacyWireName` today and the `ServerWire` registry is empty, so clients below version 8 get the same output as from the plain formats. The mechanism in `server/src/main/kotlin/dev/dertyp/core/wire/` stays for the next rename.
- Raising `ApiVersion.LEGACY` (currently 1) is what makes a rule dead. Update `ClientInfo.fromHeaders` expectations and the tests when you do.

## UI schema version

The UI schema version is `UiSchemaVersion.CURRENT` ([UiSchemaVersion.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/ui/UiSchemaVersion.kt)). Each component is registered with its introducing version in `UiSchema.introducedIn` and `UiSchemaCompat` downgrades newer ones to `Fallback` or, for `FileField`, to a base64 `TextField` ([UiSchemaCompat.kt](../server/src/main/kotlin/dev/dertyp/utils/UiSchemaCompat.kt)). See [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md).

### No target version yet

- `UiComponent.FileField` (introduced in schema version 2): the `asTextField` downgrade in `UiSchemaCompat` and `BASE64_HINT` can go once clients below schema version 2 are unsupported. When schema version 1 is dropped, `UiSchemaCompat` itself and the `introducedIn` map can be reduced the same way.

## Plugin API version

The plugin API version is `ISynaraPlugin.apiVersion` ([ISynaraPlugin.kt](../plugin-api/src/main/kotlin/dev/dertyp/plugins/ISynaraPlugin.kt), default 1). The server accepts plugins up to `PluginManager.CURRENT_API_VERSION` (currently 4) and the history is in [PLUGINS.md](PLUGINS.md). Nothing in `plugin-api` is annotated `@Deprecated` today.

### No target version yet

- Nothing is deprecated. When you drop support for old plugins, raise the lowest accepted version in `PluginManager` and remove the `PluginContext` members only older versions needed.

## Other versioned things

### No target version yet

- Audio timeline format: `AudioTimelineCodec.VERSION` (currently 3) is stored in `SongAudioTimelineTable.version` and rows with a lower value are re-analysed by `AudioAnalysisService`. A format change bumps the constant. Nothing is deprecated.
- Credential protocol: `CredentialProtocol.PROTOCOL_VERSION` (currently 1, in `common-credentials`) is reported by the credential server health endpoint as `protocolVersion`. See [CREDENTIAL_SERVER.md](CREDENTIAL_SERVER.md). Nothing is deprecated.
- `ReverseProxyService` carries two `@Deprecated(level = ERROR)` overrides (`host` and `port`) of Ktor's `RequestConnectionPoint`. They belong to the Ktor interface and are not Synara deprecations, so there is nothing to remove.

## Conventions

- Mark deprecated API with a constant per target version: `@Deprecated(REMOVED_IN_API_N + " Use x.", ReplaceWith("x()"))`. Leave out `ReplaceWith` when the replacement is not a drop-in call. No such constant exists at the moment. The next deprecation creates its `REMOVED_IN_API_N` constant in [Compatibility.kt](../common-rpc/src/commonMain/kotlin/dev/dertyp/rpc/annotations/Compatibility.kt), next to `LegacyWireName`.
- A renamed method keeps its old name as a `@Deprecated` method with a default body in the common-rpc interface that delegates to the new name. Servers and client wrappers implement only the new name. kRPC keeps serving the old name because `RpcDeclarationScanner` registers every declared function.
- Every deprecated RPC method gets a default body in the common-rpc interface, so clients never override it. A deprecation without a rename delegates to its replacement as closely as it can, for example a one-shot flow over the matching getter. The server keeps its own override where older clients need the full behaviour until removal and marks it `@Deprecated` with the same message, so the override itself raises no warning.
- The generated REST handlers (`server/build/generated/ksp/.../*Rest.kt`) and the generated `NativeDispatchers.kt` of common-rpc call every deprecated method. Their generators write a file-level `Suppress("DEPRECATION")` into those files, so the generated code raises no deprecation warnings. Hand-written code never suppresses deprecation warnings and keeps them visible. The one exception is the two Ktor `host` and `port` overrides in `ReverseProxyService` (see above), which carry `@Suppress("DEPRECATION")` because the interface members they override are deprecated. Suppressions of other warning kinds, such as `UNCHECKED_CAST`, are not covered by this rule. The generated `Defaults` objects of the REST layer copy the `@Deprecated` annotation onto their probe overrides.
- Wire renames switch to the new name immediately in common-rpc. Only the server keeps the old name, for older clients, through `@LegacyWireName` on the renamed property or class. Clients never carry compatibility code and use the plain generated serializers. The server picks the legacy formats in `jsonFor` and `cborFor` ([WireFormats.kt](../server/src/main/kotlin/dev/dertyp/core/WireFormats.kt)), today for clients below `ClientFeature.FIELD_RENAMES`. A rename that targets a later version adds its own `ClientFeature` and extends that check.
- A renamed field that is reachable through a sealed class needs that sealed class with all its subclasses in the `ServerWire` registry ([WireFormats.kt](../server/src/main/kotlin/dev/dertyp/core/WireFormats.kt)). `LegacyWireRegistryTest` fails when one is missing. The registry is empty at the moment. The tests of the mechanism use annotated fixture classes in `server/src/test/kotlin/dev/dertyp/core/wire/fixtures/`.
- Parameters are never renamed, because REST derives its keys from them.
- A parameter added to an RPC method that released clients call is nullable, and null means the default. kRPC does not apply Kotlin default values for arguments an older client did not send. A missing non-null argument fails the call before the service is reached, and a missing nullable one arrives as null. The server maps null to the default in its RPC adapter. `KrpcMissingDefaultArgumentsTest` pins this.
- Old methods whose replacement derives the same REST route get `@RestExclude`.
- Every new deprecation gets an entry in this file.
