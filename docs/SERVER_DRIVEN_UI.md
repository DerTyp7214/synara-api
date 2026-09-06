# Server-Driven UI

Synara can describe parts of the user interface on the server. The server and its plugins send **component trees** that clients render with their own native widgets, already translated into the user's language. Plugins extend every client at once without client releases, and clients keep their native look because the vocabulary is semantic (a `Card`, a `Stat`, a `Button`), never visual (no colors, pixels or HTML).

This document is for client implementers. Plugin authors should read the [plugin guide](PLUGINS.md#server-driven-ui) as well. The full RPC reference is in [RPC_SERVICES.md](RPC_SERVICES.md) under `IUiService`.

## Concepts

| Term | Meaning |
|---|---|
| **Contribution** | One unit of server UI with an id like `core.importer` or `musicbrainz.settings`. Kinds: `SLOT`, `PAGE`, `HOME_CARD`. |
| **Slot** | A named hole in a native screen that the client fills with `renderSlot(slot)`. Known slots live in `UiSlots`: `library`, `settings`, `admin.dashboard`, `home`, `importer`, `album.detail`, `artist.detail`, `song.detail`, `song.menu`, `playlist.detail`, `collection.detail`. |
| **Page** | A whole screen owned by a contribution, opened through `UiAction.OpenPage(pageId)`. |
| **Home card** | A contribution the user can pin to the home screen; pin state is stored per user on the server. |
| **Portal** | A `Native` component: a hole inside a server tree that the *client* fills with native UI (`barcodeScanner`, `externalSearch`). |
| **Hook** | An app-level event (a shared URL or text) the client forwards to the server, which answers with the contributions offering to handle it. |

## Headers and handshake

Send two headers on every request, including the websocket upgrade for `/rpc`:

| Header | Value | Purpose |
|---|---|---|
| `X-Ui-Schema-Version` | `UiSchemaVersion.CURRENT` of the `common-rpc` you compiled against (currently `1`) | The server replaces components your build doesn't know with `Fallback`. Omit it and **every** component becomes `Fallback`. |
| `Accept-Language` | e.g. `de-AT, de;q=0.9, en;q=0.5` | All text in the tree arrives translated (highest-quality language wins, fallback `en`). Changing the app language means reconnecting the RPC websocket, because headers are read at upgrade time. |

Kotlin clients using `BaseRpcServiceManager` can call `uiHeaders(locale)` next to `apiVersionHeader()`.

`IHandshakeService.handshake()` returns `uiSchemaVersion`; `0` means the server has no server-driven UI.

## The vocabulary

Every node is a `UiComponent`. On the wire (JSON) each node carries `"type"` with its serial name; nested trees use the same shape recursively.

### Containers

| Type | Fields | Render as |
|---|---|---|
| `column` | `children`, `spacing`, `align`, `weights?` | Vertical stack. `weights` are relative sizes per child when the container has a fixed height. |
| `row` | `children`, `spacing`, `align`, `weights?` | Horizontal stack; wrap or scroll on narrow screens. `weights` of `0.0` mean "intrinsic size". |
| `grid` | `children`, `columns`, `spacing` | Grid; reduce `columns` on small screens. |
| `card` | `children`, `title?`, `subtitle?`, `icon?`, `tone`, `actions` | Elevated surface with optional header; `actions` (usually buttons) go in the footer. |
| `section` | `children`, `title`, `collapsible`, `collapsed` | Titled group. |
| `form` | `id`, `children`, `submit` (`invoke` action), `submitLabel`, `cancelLabel?`, `actions` | Collect the values of all fields inside and send them with the submit action. The submit button sits in a trailing row; `actions` (e.g. a scanner portal) are rendered in that same row, before it. Disable submit while a `required` field is empty. |

### Leaves

| Type | Fields | Notes |
|---|---|---|
| `text` | `text`, `style` (`TITLE`/`SUBTITLE`/`BODY`/`CAPTION`/`CODE`), `tone`, `emphasis` | `CODE` is monospaced and preserves newlines. |
| `icon` | `icon` (`UiIcon`), `tone` | A standalone icon, see *Icons*. |
| `image` | `imageId?`, `url?`, `rounded` | `imageId` refers to `IImageService`. |
| `badge` | `text`, `tone`, `icon?` | Small status chip / capsule, optional leading icon. |
| `stat` | `label`, `value`, `unit?`, `icon?`, `tone` | Big number with a label. |
| `progress` | `value?` (0–1), `label?` | `null` = indeterminate. |
| `tile` | `title`, `subtitle?`, `icon?`, `action?`, `tone` | Tappable entry, e.g. in the library slot. |
| `button` | `label`, `action`, `style`, `icon?`, `enabled` | |
| `listItem` | `title`, `subtitle?`, `icon?`, `trailing?`, `action?` | |
| `table` | `columns`, `rows[{cells, action?}]` | |
| `spacer` / `divider` | | |
| `native` | `name`, `params`, `fallback?` | Portal, see below. |
| `emptyState` | `title`, `description?`, `icon?`, `actions` | "Nothing here" placeholder: large muted icon, bold title, secondary description, all centered in the available space (iOS `ContentUnavailableView`), optional buttons below. |
| `log` | `lines`, `maxLines` | Fixed-height (~300pt) pane of small monospaced secondary text, one entry per line, scroll anchored to the newest line; keep at most `maxLines`. |
| `live` | `key`, `child` | Render `child`, then subscribe to `subscribeLive(contributionId, key)` and apply updates to this subtree only — see *Live values*. |
| `fallback` | `text?` | Inserted by the server for components you don't support. Show `text` or a generic "update the app" hint. |

### Form fields

All fields have `key` (the payload key), `label`, `helper?`, `error?`, `required`, `enabled`.

| Type | Extra fields | Payload value |
|---|---|---|
| `textField` | `value?`, `placeholder?`, `secret`, `multiline`, `kind`, `toolbar` | `{"text": "…"}` |
| `numberField` | `value?`, `min?`, `max?`, `step?` | `{"number": 3.0}` |
| `switch` | `value` | `{"flag": true}` |
| `select` | `value?`, `options[{value,label,icon?}]` | `{"text": "<option value>"}` |

`secret` fields never contain the stored value; submitting an empty value means "unchanged". `kind` hints at the keyboard: `URL`, `EMAIL`, `BARCODE` (offer a scanner), `MULTILINE_URLS` (one entry per line, a scanner appends a line). `toolbar` is the **keyboard accessory toolbar**: shown above the on-screen keyboard while the field is focused (iOS `.keyboard` toolbar placement, Android IME accessory row; on desktop render it inline under the field), items trailing-aligned — typically a single `button` with `dismissKeyboard`.

### Icons

Every `icon` field is a `UiIcon`, a sealed type so clients have a real mapping table instead of guessing names:

| Type | Fields | Render as |
|---|---|---|
| `named` | `name` (`UiIconName`) | One of your icon set: `SETTINGS`, `MUSIC`, `ALBUM`, `ARTIST`, `PLAYLIST`, `IMAGE`, `STORAGE`, `STATS`, `TASK`, `PLAY`, `PAUSE`, `DOWNLOAD`, `IMPORT`, `QUEUE`, `PLUG`, `LOGIN`, `HEART`, `SYNC`, `SEARCH`, `KEY`, `DATABASE`, `WARNING`, `ERROR`, `INFO`, `USER`, `CHECK`, `CLOSE`, `LINK`, `FILE`, `MORE`, `BARCODE`. Map each to a concrete glyph (e.g. iOS `QUEUE` → `list.bullet.clipboard`, `SYNC` → `arrow.triangle.2.circlepath`, `BARCODE` → `barcode.viewfinder`, `IMPORT` → `square.and.arrow.down`). |
| `url` | `url` | Remote image at icon size. |
| `image` | `imageId` | Server image (`IImageService`) at icon size. |

New names are only added with a UI schema version bump, so a client never meets an unknown name.

Tones map to your design system: `DEFAULT`, `PRIMARY` (accent), `SUCCESS`, `WARNING`, `ERROR`, `MUTED`.

## Actions

| Type | Fields | Client behaviour |
|---|---|---|
| `invoke` | `contributionId`, `actionId`, `params`, `formId?`, `confirmText?` | If `confirmText` is set, ask first. Build `UiInvokePayload(values = params + form values of formId, context)` and call `invoke(contributionId, actionId, payload)`. |
| `openEntity` | `entityType`, `entityId` | Open the native song/album/artist/playlist/user screen. |
| `openPage` | `pageId`, `params`, `modal` | Open the page renderer for `pageId`; pass `params` as `UiContext.params` to `render`/`subscribe`. `modal = true` asks for a sheet/dialog with a close control instead of a push. |
| `dismissKeyboard` | | Unfocus the current input and close the on-screen keyboard (no-op on desktop). |
| `openUrl` | `url` | Open in the system browser (used for OAuth logins). |
| `openNative` | `name`, `params` | Open a native screen by portal name, e.g. `externalSearch` with `query`. |
| `openMenu` | `items[{label, action, icon?, tone, enabled}]`, `title?` | Show a native menu anchored to the element (dropdown on desktop, context menu or bottom sheet on mobile) and perform the chosen item's `action`. An item's action may be another `openMenu` (sub-menu). A "menu button" is simply a `button` whose action is `openMenu`; a `listItem` or `tile` can open a menu the same way. |
| `refresh` | | Re-render the contribution. |

`UiInvokeResult` carries `status` (`OK`, `VALIDATION_ERROR`, `ERROR`, `UNAUTHORIZED`), an optional `message` to toast, `fieldErrors` (key → text, show next to the field), `refresh` (re-render now) and `next` (perform this action afterwards, e.g. `openUrl`).

## Portals

A `native` node names a hole for your own UI. Implement at least:

| Name | `params` | What to render |
|---|---|---|
| `barcodeScanner` | `target` = key of the form field to fill | A scan button; append the scanned code as a new line to that field (or replace its value for single-line fields). |
| `externalSearch` | `query?` | Your external-search screen (also reachable through `openNative`). |

If you don't implement a name, render `fallback` if present, otherwise nothing.

## Reading and subscribing

- `listContributions(kind?, slot?)` – discover what exists (`UiContributionInfo` includes `title`, `icon`, `live`, `hooks`, access flags).
- `renderSlot(slot, context)` – one call per native screen; render `items` in order.
- `render(contributionId, context)` – one-off render, e.g. a page.
- `subscribe(contributionId, entityId?)` – a `Flow<UiRender>` that emits immediately and again on every change; use it for pages and live cards. `revision` increases per emission; only re-render when `root` or `toolbar` actually changed.

### Live values

Re-rendering a whole page for every log line would be wasteful, so high-frequency data uses a second channel. A `live` node marks a subtree; the client renders its `child` immediately and opens `subscribeLive(contributionId, key, entityId?)` — a `Flow<UiLiveUpdate>`:

| Update | Effect on the `live` child |
|---|---|
| `replace` `{child}` | Swap the whole child for the new component. |
| `appendLines` `{lines}` | Append to a `log` child and drop the oldest lines beyond its `maxLines`. |

Rules: one subscription per `live` node while it is on screen; cancel it when the node disappears; when the page tree is re-rendered by `subscribe`, keep existing live subscriptions whose `key` is still present and open/close the rest. The page tree itself only changes on structural events (the importer re-renders on queue changes and login-state changes, never on log output).

### Pages and the toolbar

A `UiRender` of a `PAGE` contribution has `title` (use it as the screen title) and `toolbar`: a list of components for the native app bar — `button`s (render as icon buttons using `icon`, with `label` as tooltip/accessibility text), `icon`s and `native` portals. A toolbar button with an `openMenu` action is the overflow menu. Nothing in `toolbar` appears in `root`, so the page body stays free of chrome. Slot items and home cards always have an empty `toolbar`.

The `song.menu` slot (and future `*.menu` slots) is the native context menu of an entity: render each item's `root` as menu entries — contributions return `listItem`s or `button`s there, whose actions you perform when chosen.

`UiContext` tells the server where the tree is shown: `entityType`/`entityId` on detail screens, `params` for pages opened with parameters.

Contributions are already filtered by the user's permissions; `render`/`invoke` on something the user may not see return 403 (`UnauthorizedException`).

## Intake

Anything a user hands to the app — a link, a catalog code, a provider id, free text (later: a file) — is an `IntakeItem`. Instead of calling importer-specific RPCs, clients hand items to the server and plugins offer to handle them:

| `IntakeItem` | Fields | Example |
|---|---|---|
| `url` | `url` | `https://tidal.com/browse/album/1` |
| `code` | `kind` (`ISRC`/`UPC`), `value` | `USRC17607839` |
| `id` | `provider`, `id`, `contentType?` | the download button in external search: `{"type":"id","provider":"tidal","id":"123","contentType":"track"}` |
| `text` | `text` | a search query |
| `file` | `fileId`, `name`, `mimeType?` | reserved for uploads |

`IntakeItem.parse(line)` in `common-rpc` classifies a pasted line the same way the server does (URL → code → `provider:id` → text).

- `intake(items, resolverId?)` → `UiIntakeResult`:
  - `OK` — everything acceptable was submitted (`accepted` count, `message` to toast, `rejected` items nobody took, optional `next`). **No navigation needed**: the external-search download button just calls `intake([id])` and shows the toast.
  - `NEEDS_CHOICE` — several handlers claim the same item (e.g. an Apple Music link that both Tidal and gamdl can import) or only navigational handlers exist (text → external search). Show `handlers` as a menu; each handler's `action` is `intake` with the handler preselected, so performing it calls `intake` again and yields `OK`.
  - `UNHANDLED` — nothing accepted any item; fall back to native behaviour.
  - `UNAUTHORIZED` / `ERROR` — show `message`.
- `resolveIntake(items)` → the handlers only, when you always want to show a chooser.
- `UiAction.Intake(items, resolverId?, confirmText?)` is the action form of the same call; buttons, menu entries and hook handlers use it.

Example — download from external search:

```http
POST /ui/intake
{"items": [{"type": "id", "provider": "tidal", "id": "123", "contentType": "track"}]}
```

```json
{"status": "OK", "message": "1 items queued", "accepted": 1}
```

Example — an ambiguous link:

```json
{"status": "NEEDS_CHOICE", "handlers": [
  {"id": "import.tidal", "contributionId": "import.tidal", "title": "Import with Tidal", "confirmText": "Import this link?",
   "action": {"type": "intake", "items": [{"type": "url", "url": "https://music.apple.com/album/1"}], "resolverId": "import.tidal", "confirmText": "Import this link?"}},
  {"id": "import.gamdl", "contributionId": "import.gamdl", "title": "Import with gamdl (Apple Music)", "action": {"type": "intake", "…": "…", "resolverId": "import.gamdl"}}
]}
```

Work accepted by an intake handler runs in server-side **jobs**, queued per kind (imports never block a favourites sync); the queue page shows their progress and offers Cancel.

## Home cards

`getHomeCards()` / `getHomeCardsFlow()` return every `HOME_CARD` contribution with `pinned` and `position`. Render pinned cards on the home screen (each through `subscribe(card.contributionId)`), offer the unpinned ones in a picker, and persist changes with `setHomeCardPinned` / `setHomeCardOrder`.

## Hooks

When the app receives a shared URL or text, call `dispatchHook(UiHookEvent.ShareUrl(url))` (or `ShareText`). The result is a list of `UiHookHandler`:

- empty → fall back to your native behaviour;
- one → perform `handler.action` directly, unless `confirmText` is set — then ask first;
- several → show a chooser with `title`/`description`/`icon`, then perform the chosen `action`.

Nothing is executed on the server during dispatch, so offering never has side effects. Shared URLs and text are parsed into `IntakeItem`s, so the handlers are the intake handlers (an `intake` action per importer that can take the link — "Import with Tidal", confirm "Import this link?") plus contribution offers such as the importer page's "Open in importer" (`openPage` with the text pre-filled) and, for plain text, "Search catalog".

## Worked example: the importer screen

The importer is modelled after the iOS importer screen: a `PAGE` contribution (`core.importer`) with the URL editor and the log pane, two modal pages opened from its toolbar (`core.importer.queue` — the queue sheet — and `core.importer.settings` — importer logins and plugin sections such as gamdl credentials), and a `library` tile (`core.importer.entry`). The mock server (`./gradlew :mock-server:run`, REST on `http://localhost:8081`, any `Authorization: Bearer x` header) serves the same trees, so every step below can be tried with `curl`.

### 1. Library entry

```http
POST /ui/renderSlot
{"slot": "library", "context": {}}
```

```json
{
  "slot": "library",
  "items": [{
    "contributionId": "core.importer.entry",
    "title": "Importer",
    "root": {"type": "tile", "title": "Importer", "subtitle": "Import music from streaming services",
             "icon": {"type": "named", "name": "IMPORT"}, "action": {"type": "openPage", "pageId": "core.importer"}}
  }]
}
```

Render the tile in your library list. Tapping it performs `openPage`.

### 2. Open the page

Navigate to your generic page screen and `subscribe("core.importer")`. The first emission is the iOS layout — two blocks and a toolbar:

```json
{
  "contributionId": "core.importer", "title": "Importer", "revision": 1,
  "toolbar": [
    {"type": "button", "label": "Queue", "style": "TEXT", "icon": {"type": "named", "name": "QUEUE"}, "action": {"type": "openPage", "pageId": "core.importer.queue", "modal": true}},
    {"type": "button", "label": "Sync Favorites", "style": "TEXT", "icon": {"type": "named", "name": "SYNC"},
     "action": {"type": "invoke", "contributionId": "core.importer", "actionId": "syncFavourites", "confirmText": "Are you sure you want to synchronize your favorites?"}},
    {"type": "button", "label": "Importer settings", "style": "TEXT", "icon": {"type": "named", "name": "SETTINGS"}, "action": {"type": "openPage", "pageId": "core.importer.settings", "modal": true}}
  ],
  "root": {"type": "column", "spacing": "LARGE", "children": [
    {"type": "column", "spacing": "SMALL", "children": [
      {"type": "text", "text": "Import URLs", "style": "TITLE"},
      {"type": "text", "text": "Enter one URL per line to import them.", "style": "CAPTION", "tone": "MUTED"},
      {"type": "form", "id": "import",
       "submit": {"type": "invoke", "contributionId": "core.importer", "actionId": "import", "formId": "import"},
       "submitLabel": "Import",
       "children": [
         {"type": "textField", "key": "input", "label": "Import URLs", "multiline": true, "required": true, "kind": "MULTILINE_URLS",
          "toolbar": [{"type": "button", "label": "Done", "style": "TEXT", "icon": {"type": "named", "name": "CHECK"}, "action": {"type": "dismissKeyboard"}}]}
       ],
       "actions": [{"type": "native", "name": "barcodeScanner", "params": {"target": "input"}}]}
    ]},
    {"type": "column", "spacing": "SMALL", "children": [
      {"type": "text", "text": "Logs", "style": "TITLE"},
      {"type": "live", "key": "log", "child": {"type": "log", "lines": ["Fetching metadata…", "Downloading 3/12"], "maxLines": 500}}
    ]}
  ]}
}
```

Mapping to the screen: headline + caption + multi-line editor (the editor's keyboard toolbar is the Done checkmark from `textField.toolbar`), then a trailing row `[scan][Import]` — the `native` portal from `form.actions` is your barcode scanner button (append scanned codes to `input`), the submit button is disabled while the field is empty. Below it the "Logs" headline and the `log` pane. The toolbar buttons become the app-bar icon buttons (Sync only appears while a sync is possible). When the default importer needs a login, a warning `card` "Login Required" with a Login button precedes the import block — the equivalent of the iOS alert.

### 3. Stream the log

Open one live subscription for the `live` node:

```http
GET /ui/subscribeLive/core.importer?key=log        (SSE)
```

```json
{"type": "appendLines", "lines": ["Downloading 4/12"]}
{"type": "appendLines", "lines": ["Downloading 5/12"]}
{"type": "appendLines", "lines": ["Tagging track 5"]}
```

Append lines to the pane (dropping the oldest beyond 500); the server keeps a rolling 500-line buffer across imports, so the pane is never cleared when an import finishes. A `replace` only arrives if a contribution deliberately swaps the subtree. The page subscription itself only re-emits on queue or login changes.

### 4. Submit the form

Collect the field values under their keys and send the form's submit action (routing to Tidal/YouTube/… is automatic on the server):

```http
POST /ui/invoke/core.importer?actionId=import
{"values": {"input": {"text": "https://tidal.com/browse/album/1234\nUSRC17607839"}}, "context": {}}
```

```json
{"status": "OK", "message": "2 items queued", "refresh": true}
```

Show `message` as a toast and clear the editor. A `VALIDATION_ERROR` returns `fieldErrors`, e.g. `{"input": "Enter at least one URL or code."}` – display it next to the field.

### 5. Queue sheet

The toolbar's Queue button is `openPage` with `modal: true`: present `subscribe("core.importer.queue")` as a sheet with a close control. The tree mirrors the iOS sheet — a stat card (`Total URLs` / `Importing`, only when URLs are queued), a "Currently Importing" header with one entry card, and a "Pending Imports" header with one card per entry; each card has an icon + "URLs"/"Favorites" title row, the joined URLs as a `listItem` whose action opens a menu listing every URL, a `progress` while it runs (value from the job when the importer reports one, otherwise indeterminate), a badge row (`TRACK`, user chip with `icon: "user"`) and a **Cancel** button (`invoke` `cancel` with the job id) in the card's `actions`. With nothing queued the root is an `emptyState` ("Queue is empty") — render it as a `ContentUnavailableView`. It re-emits on every queue change, so there is no polling.

### 6. Log in and importer settings

The settings gear opens `core.importer.settings` (modal): an "Importers" section listing every enabled importer with its status badge and a Login button where needed, followed by collapsed sections contributed by plugins (e.g. gamdl's Apple Music credentials form). A Login `invoke` returns

```json
{"status": "OK", "message": "Complete the login in your browser, then come back.", "refresh": true,
 "next": {"type": "openUrl", "url": "https://example.org/login"}}
```

Perform `next` (open the browser). When the login completes, both the settings page and the main page re-render with the importer marked "Authorized" and the login card gone.

### 7. Share intent

A URL shared to the app:

```http
POST /ui/dispatchHook
{"event": {"type": "shareUrl", "url": "https://music.apple.com/album/1"}}
```

```json
[
  {"id": "import.tidal", "contributionId": "import.tidal", "source": "server", "title": "Import with Tidal", "icon": {"type": "named", "name": "IMPORT"}, "confirmText": "Import this link?",
   "action": {"type": "intake", "items": [{"type": "url", "url": "https://music.apple.com/album/1"}], "resolverId": "import.tidal", "confirmText": "Import this link?"}},
  {"id": "import.gamdl", "contributionId": "import.gamdl", "source": "server", "title": "Import with gamdl (Apple Music)", "icon": {"type": "named", "name": "IMPORT"}, "confirmText": "Import this link?",
   "action": {"type": "intake", "items": [{"type": "url", "url": "https://music.apple.com/album/1"}], "resolverId": "import.gamdl", "confirmText": "Import this link?"}},
  {"id": "core.importer", "contributionId": "core.importer", "source": "server", "title": "Open in importer", "icon": {"type": "named", "name": "IMPORT"},
   "action": {"type": "openPage", "pageId": "core.importer", "params": {"input": "https://music.apple.com/album/1"}}}
]
```

Show the chooser; picking an "Import with …" entry performs its `intake` action (confirm, then `intake(items, resolverId)` → `OK` toast) without ever opening the importer page; "Open in importer" opens the page with the input pre-filled. A Tidal link yields one `intake` handler plus "Open in importer".

### Checklist for a client

1. Send `X-Ui-Schema-Version` and `Accept-Language` on every connection.
2. Implement a `UiComponent` renderer for the vocabulary above, with `Fallback` handling, `live` subscriptions and modal pages.
3. Implement the `barcodeScanner` and `externalSearch` portals.
4. Replace the hardcoded importer entry with `renderSlot("library")` and the importer, queue and settings screens with the page renderer (`subscribe("core.importer")` etc.).
5. Route share intents through `dispatchHook`, and replace native `importIds`/`importUrls` calls (external search download button, album import, recent releases) with `intake(items)` + toast/chooser.
6. Render the home cards (`core.importer.card` ships: queue stats, current import, buttons into the importer and queue pages) and optionally the `settings` / `admin.dashboard` slots for plugin contributions.

## Evolving the schema

New components or enum entries are only added together with a bump of `UiSchemaVersion.CURRENT`; the server downgrades trees for older clients. Clients never need to handle unknown `type` values as long as they send the header.
