# Synara Custom Plugins

Synara supports an extensible plugin architecture that allows you to add custom importers, indexers, routes, scheduled tasks and server-driven UI.

## Plugin Structure

A Synara plugin is a Java/Kotlin project that implements the `ISynaraPlugin` interface from the `plugin-api` module.

### `ISynaraPlugin` Interface

```kotlin
interface ISynaraPlugin {
    val id: String
    val name: String
    val apiVersion: Int get() = 1
    val enabled: Boolean get() = true

    fun init(context: PluginContext)
    fun getKoinModule(): Module? = null
}
```

Additional capabilities are opt-in through marker interfaces your plugin class can implement as well:

| Interface | Purpose |
|---|---|
| `IContentSourcePlugin` | Provide `IImporter`s, `IPluginIndexer`s and metadata services. |
| `IRoutePlugin` | Register raw Ktor routes. |
| `IUiPlugin` | Contribute server-driven UI (see below). |

### `PluginContext`

Passed to `init`. Provides the logger, storage, the library APIs (`songLibrary`, `albumLibrary`, `artistLibrary`, `playlistLibrary`, `imageLibrary`), metadata and lyrics services, the `scheduleService` for scheduled tasks, the `hooks` bus, `apiKeyScopes`, and — since API version 2 — `ui`, `settings` and `i18n`.

The `hooks` bus emits `HookEvent`s a plugin can subscribe to with `hooks.on<E> { }`: `ListenIngested` (listens arrived from ListenBrainz), `PlaylistChanged` and `CollectionChanged` (content of a user playlist or collection changed), and `NowPlayingChanged` (a user started or stopped playing a song; `songId` is null on stop, `generation` increases per change so stale events can be dropped).

## Creating a Plugin

1.  **Depend on `plugin-api`**: Add the Synara `plugin-api` module as a dependency in your project.
2.  **Implement `ISynaraPlugin`**: Create a class that implements `ISynaraPlugin` and any of the marker interfaces above.
3.  **Register via `ServiceLoader`**:
    -   Create a file named `dev.dertyp.plugins.ISynaraPlugin` in `src/main/resources/META-INF/services/`.
    -   Add the fully qualified name of your implementation class to this file.

## Installing a Plugin

1.  **Build the JAR**: Package your plugin as a shadow/fat JAR (including all non-provided dependencies).
2.  **Deploy**: Place the resulting `.jar` file into the `plugins/` directory in the Synara root.
3.  **Restart**: Restart the Synara server. You should see "Loaded plugin: [Your Plugin Name]" in the logs.

## Server-Driven UI

Plugins can add UI to every Synara client without touching client code. The server sends component trees that clients render natively; see [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md) for the vocabulary and the client side.

### Contributions

Implement `IUiPlugin` and return `UiContribution`s:

```kotlin
class MyPlugin : ISynaraPlugin, IUiPlugin {
    override val id = "myplugin"
    override val name = "My Plugin"
    override val apiVersion = 2

    override fun init(context: PluginContext) {
        context.i18n.registerBundlesFromResources(javaClass.classLoader, "i18n/myplugin", listOf("en", "de"))
    }

    override fun getUiContributions() = listOf(MySettings())
}

class MySettings : UiContribution(
    id = "myplugin.settings",          // [a-z0-9._-]+
    kind = UiContributionKind.SLOT,     // SLOT, PAGE or HOME_CARD
    slot = UiSlots.SETTINGS,
    titleKey = "myplugin.settings.title",
    icon = "settings",
    access = UiAccess(requiresAdmin = true),
) {
    override suspend fun render(scope: UiRenderScope): UiComponent {
        val current = scope.settings.get("apiKey")
        return UiComponent.Card(
            title = scope.t("myplugin.settings.title"),
            children = listOf(
                UiComponent.Form(
                    id = "settings",
                    submit = UiAction.Invoke(id, "save", formId = "settings"),
                    submitLabel = scope.t("myplugin.settings.save"),
                    children = listOf(
                        UiComponent.TextField("apiKey", scope.t("myplugin.settings.apiKey"), secret = true, required = current == null),
                    ),
                ),
            ),
        )
    }

    override fun changes(scope: UiRenderScope): Flow<Unit> = scope.settings.changes().map { }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        val key = values["apiKey"]?.text?.trim().orEmpty()
        if (key.isEmpty()) return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf("apiKey" to scope.t("myplugin.settings.error.apiKey")))
        scope.settings.set("apiKey", key)
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("myplugin.settings.saved"), refresh = true)
    }
}
```

- `render` is called with a `UiRenderScope` carrying the user (`scope.user`), the host `context` (entity or page params), a translator (`scope.t`), the plugin's `settings`, and the client's schema version.
- `toolbar` (pages only) returns components for the native app bar — buttons with icons, portals — so the page body holds content only.
- `live(scope, key)` serves a `UiComponent.Live(key, child)` node in your tree: return a `Flow<UiLiveUpdate>` (`Replace(child)` or `AppendLines(lines)` for a `Log` child) so frequent data such as process output updates that subtree only, without re-rendering the page. Return `null` for unknown keys. Example: `UiComponent.Live("log", UiComponent.Log(currentLines))` in `render`, and `live` returning `process.output.map { UiLiveUpdate.AppendLines(listOf(it)) }`.
- `changes` returns a `Flow<Unit>` when the contribution is live; the server re-renders subscribers on each emission. Return `null` (the default) for static content.
- `invoke` handles `UiAction.Invoke` from the client. Throw `IllegalArgumentException` or return `VALIDATION_ERROR` with `fieldErrors` for bad input; set `refresh = true` to re-render, and `next` to make the client perform a follow-up action (e.g. `UiAction.OpenUrl` for OAuth).
- `hooks` + `onHook` let a contribution offer to handle app events such as a shared URL. Return a `UiHookOffer` describing the action (optionally with a `confirmKey` so the client asks before performing it); the client lets the user choose when several plugins offer.
- `access` restricts who sees and may invoke the contribution (`requiresAdmin`, required `UserCapability`s). Authorization is enforced by the server.
- Use `context.ui.invalidate(id)` to push a re-render from outside the flow.

Slots available today: `library`, `settings`, `admin.dashboard`, `home` (home cards), `importer` (sections on the importer page), `album.detail`, `artist.detail`, `song.detail`, `song.menu`, `playlist.detail`.

Secret fields (`TextField(secret = true)`) must never echo the stored value; treat an empty submitted value as "unchanged".

### Settings

`context.settings` (and `scope.settings`) is a per-plugin key/value store persisted in the database. Values are strings; parse them yourself. `changes()` emits the full map whenever something is written.

### Intake resolvers

Users hand the app links, catalog codes, ids and text (`IntakeItem`, see [SERVER_DRIVEN_UI.md](SERVER_DRIVEN_UI.md#intake)). Register an `IntakeResolver` through `context.intake` to offer handling them — the importer is one resolver per backend, plugins can add their own:

```kotlin
context.intake.register(object : IntakeResolver {
    override val id = "myplugin.mbid"
    override val titleKey = "myplugin.intake.title"          // "Look up on MusicBrainz"
    override val jobKind = "myplugin"
    override val access = UiAccess(capabilities = setOf(UserCapability.IMPORT))

    override suspend fun offer(items: List<IntakeItem>, user: UserInfo): IntakeOffer? {
        val mine = items.filterIsInstance<IntakeItem.Id>().filter { it.provider == "mbid" }
        if (mine.isEmpty()) return null
        return IntakeOffer(accepted = mine, confirmKey = "myplugin.intake.confirm", submit = {
            context.jobs.enqueue(jobKind, "MusicBrainz lookup", user, mine.joinToString { it.id }) {
                mine.forEachIndexed { index, item ->
                    log("Resolving ${item.id}")
                    progress((index + 1).toDouble() / mine.size)
                    if (!isActive()) return@enqueue
                }
            }
            IntakeReceipt(accepted = mine.size, messageKey = "myplugin.intake.queued")
        })
    }
})
```

- `offer` returns the subset it accepts. Either `submit` (does the work, usually by enqueuing a job, and returns an `IntakeReceipt`) or `action` (navigational, e.g. open a page) — never both.
- When several resolvers accept the same item the server does not pick: the client shows a chooser and calls back with the chosen resolver. `priority` orders the chooser; `titleArgs` fill `{placeholders}` in the title key.
- `access` is enforced before `offer` is called.

### Jobs

`context.jobs` is a per-kind FIFO queue with one running job per kind (kinds run in parallel): `enqueue(kind, title, user, summary) { … }` returns the job id; inside the body use `log(line)`, `progress(value, message)` and `isActive()` (false once cancelled). `jobs(kind, user)` streams `JobInfo`s (pending/running/finished with progress), `log(jobId)` streams a job's lines, `cancel(jobId)` cancels your own source's jobs. Imports run in kind `"import"`, favourites syncs in `"favourites"`.

### Translations

All text in a tree must be translated on the server. Register bundles with `context.i18n`:

- `registerBundlesFromResources(classLoader, "i18n/myplugin", listOf("en", "de"))` loads `i18n/myplugin/en.json` etc. from your jar — flat JSON objects of key → text.
- `registerBundle(locale, messages)` registers or replaces a bundle at any time, e.g. after fetching translations remotely; live subscribers are re-rendered.
- Lookup order for `scope.t(key)`: your plugin's exact locale → language → `en`, then the core bundles the same way, then the key itself. Placeholders use `{name}`: `scope.t("greeting", "user" to name)`.

## Versioning

Plugins specify an `apiVersion`. Synara will only load plugins with an `apiVersion` less than or equal to the server's current supported version. This ensures backward compatibility as the plugin API evolves.

Current Supported API Version: **2** (adds `ui`, `settings` and `i18n` to `PluginContext` and the `IUiPlugin` interface).
