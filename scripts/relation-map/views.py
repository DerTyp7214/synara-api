import re
from collections import Counter, defaultdict

from layout import blocks_layout, columns_layout

DB_RULES = [
    (r"^(user_entity_change|entity_change.*)$", "Change tracking"),
    (r"^(listen|listen_link|listen_backup_config|listenbrainz_user|user_listenbrainz_link)$", "Listening history"),
    (r"^mb_", "MusicBrainz mirror"),
    (r"^(custommigration|rpc_call_.*|scheduled_task_.*|search_index_queue|pluginsetting)$", "Operations"),
    (r"^hue_", "Hue"),
    (r"^podcast", "Podcasts"),
    (r"^radiochannel", "Radio"),
    (r"^(provider_.*|recent_release.*|release_artist|hidden_release|followed_artist)$", "Releases and providers"),
    (r"^(playlist.*|userplaylist.*|collection.*)$", "Playlists and collections"),
    (r"^(image|image_metadata|animated_image)$", "Images"),
    (r"^(usersong|useralbum|favsync|syncservice|userqueue.*|queuesyncdevice|userhomecard)$", "User library and queue"),
    (r"^(user|user_capability|session|apikey|refreshtoken|subsoniccredential|clientdevice|clientsetting.*)$", "Users and devices"),
    (r"^(album.*)$", "Albums"),
    (r"^(artist.*|person)$", "Artists"),
    (r"^(song.*|genre|flac_info|pcm_info|synced_lyrics|timecodetag|transcodedsong)$", "Songs"),
]
DB_ORDER = [
    "Users and devices", "User library and queue", "Listening history", "Playlists and collections", "Radio",
    "Songs", "Albums", "Artists", "Images", "Podcasts",
    "MusicBrainz mirror", "Releases and providers", "Change tracking", "Hue", "Operations",
]
DB_STYLES = {"CASCADE": ("", "on delete cascade"), "SET NULL": ("dash", "on delete set null"), "RESTRICT": ("dot", "on delete restrict"), "NO ACTION": ("dot", "on delete no action"), "SET DEFAULT": ("dash", "on delete set default")}

BANDS = [
    ("entry", "Entry points", 0, 1),
    ("features", "Feature services", 1, 1),
    ("services", "Core services", 2, 1),
    ("plugins", "Plugins", 2, 1),
    ("core", "Core", 3, 1),
    ("db", "Database tables", 4, 1),
    ("support", "Support", 4, 1),
    ("modules", "Other Gradle modules", 5, 1),
]
ENTRY_PACKAGES = {"(root)", "routing", "routing.rest", "mcp", "services.subsonic", "docs"}
SUPPORT_PACKAGES = {"utils", "config", "serializers", "audio"}

CLASS_GROUPS = [
    ("entry", "Routing and RPC adapters"),
    ("library", "Library"),
    ("listening", "Listening"),
    ("metadata", "Metadata"),
    ("import", "Import, intake and jobs"),
    ("release", "Releases"),
    ("schedule", "Scheduled workers"),
    ("audio", "Audio"),
    ("podcast", "Podcasts"),
    ("credentials", "Credentials"),
    ("ui", "Server-driven UI"),
    ("cover", "Covers"),
    ("sync", "Sync"),
    ("hue", "Hue"),
    ("subsonic", "Subsonic"),
    ("auth", "Auth"),
    ("system", "System"),
    ("core", "Core"),
]
KOIN_GROUPS = {"intake": "import", "config": "core", "mcp": "entry"}
PACKAGE_GROUPS = [
    ("services.schedule", "schedule"), ("services.credentials", "credentials"), ("services.ui", "ui"), ("services.metadata", "metadata"),
    ("services.import", "import"), ("services.intake", "import"), ("services.jobs", "import"), ("services.gamdl", "import"),
    ("services.youtube", "import"), ("services.soundcloud", "import"), ("services.podcast", "podcast"), ("services.cover", "cover"),
    ("services.sync", "sync"), ("services.hue", "hue"), ("services.subsonic", "subsonic"), ("services.release", "release"),
    ("services.recommendation", "listening"), ("services.audio", "audio"), ("audio", "audio"), ("routing", "entry"), ("mcp", "entry"),
    ("core", "core"), ("config", "core"), ("plugins", "core"), ("utils", "core"), ("db", "core"), ("serializers", "core"), ("docs", "core"),
]
CLASS_OVERRIDES = {"PluginHooks": "system", "ApiClient": "core", "Indexer": "library"}
SKIPPED_CLASSES = {"ServiceRegistrar"}


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def db_group(table):
    for pattern, group in DB_RULES:
        if re.match(pattern, table):
            return group
    raise SystemExit("no group for table " + table)


def database_view(schema, base_name):
    references = defaultdict(dict)
    for f in schema["fks"]:
        for column in f["cols"]:
            references[f["from"]][column] = f["to"]
    nodes = []
    row_index = {}
    for t in schema["tables"]:
        columns = []
        rows = []
        for c in t["columns"]:
            flags = []
            if c["name"] in t["pk"]:
                flags.append("PK")
            if c["name"] in references[t["name"]]:
                flags.append("FK")
            if any(u == [c["name"]] for u in t["unique"]):
                flags.append("UQ")
            columns.append([c["name"], c["type"], " ".join(flags), 1 if c["nullable"] else 0])
            if "PK" in flags or "FK" in flags:
                row_index[(t["name"], c["name"])] = len(rows)
                rows.append([c["name"], references[t["name"]].get(c["name"], ""), "PK" if "PK" in flags else ""])
        nodes.append({"id": t["name"], "label": t["name"], "group": slug(db_group(t["name"])), "cols": columns, "rows": rows,
                      "meta": [["Columns", str(len(columns))], ["Primary key", ", ".join(t["pk"]) or "none"]]})
    edges = []
    used = []
    for f in schema["fks"]:
        if f["onDelete"] not in used:
            used.append(f["onDelete"])
        edges.append({"from": f["from"], "to": f["to"], "label": ", ".join(f["cols"]), "kind": slug(f["onDelete"]),
                      "note": "%s.%s references %s.%s, %s" % (f["from"], ", ".join(f["cols"]), f["to"], ", ".join(f["toCols"]), DB_STYLES[f["onDelete"]][1]),
                      "row": row_index[(f["from"], f["cols"][0])]})
    groups = [{"id": slug(name), "label": name} for name in DB_ORDER]
    result = blocks_layout(nodes, edges, groups, order=[g["id"] for g in groups], ratio=2.3, stubs=True)
    incoming = Counter(f["to"] for f in schema["fks"])
    view = {
        "id": "database", "tab": "Database", "noun": "table",
        "blurb": "Every table of the PostgreSQL base schema (%s) with its key columns and foreign keys. Arrows point from the table holding the key to the table it references. Keys that leave a domain are shown as a short tick at the column and drawn in full when you select either table." % base_name,
        "out": "References", "inn": "Referenced by", "far": "stub", "labels": False,
        "groups": groups,
        "styles": [{"kind": slug(k), "dash": DB_STYLES[k][0], "label": DB_STYLES[k][1]} for k in ("CASCADE", "SET NULL", "RESTRICT", "NO ACTION", "SET DEFAULT") if k in used],
        "notes": [{"title": "Hub tables", "text": "Most foreign keys point at a few tables: %s." % ", ".join("%s (%d)" % (name, count) for name, count in sorted(incoming.items(), key=lambda item: (-item[1], item[0]))[:6])}],
    }
    view.update(result)
    return view


def package_band(package):
    if package.startswith("@"):
        return "modules"
    if package in ENTRY_PACKAGES:
        return "entry"
    if package == "services":
        return "services"
    if package.startswith("services."):
        return "features"
    if package == "plugins":
        return "plugins"
    if package == "core" or package.startswith("core."):
        return "core"
    if package == "db":
        return "db"
    if package in SUPPORT_PACKAGES:
        return "support"
    raise SystemExit("no band for package " + package)


def package_id(package):
    if package.startswith("@"):
        return "mod-" + slug(package[1:])
    return "root" if package == "(root)" else package


def packages_view(code):
    order = [band[0] for band in BANDS]
    names = sorted(p["id"] for p in code["packages"]) + sorted({e["to"] for e in code["packageEdges"] if e["to"].startswith("@")})
    files = {p["id"]: p["files"] for p in code["packages"]}
    nodes = []
    band_of = {}
    for name in names:
        band = package_band(name)
        band_of[name] = band
        label = ":" + name[1:] if name.startswith("@") else name
        meta = [["Kind", "Gradle module"]] if name.startswith("@") else [["Files", str(files[name])], ["Package", "dev.dertyp" if name == "(root)" else "dev.dertyp." + name]]
        nodes.append({"id": package_id(name), "label": label, "group": band, "meta": meta})
    nodes.sort(key=lambda n: (order.index(n["group"]), n["id"]))
    edges = []
    upward = []
    for e in sorted(code["packageEdges"], key=lambda e: (-e["n"], e["from"], e["to"])):
        if e["from"] not in band_of or e["to"] not in band_of:
            continue
        up = order.index(band_of[e["to"]]) < order.index(band_of[e["from"]])
        edge = {"from": package_id(e["from"]), "to": package_id(e["to"]), "label": "%d import%s" % (e["n"], "" if e["n"] == 1 else "s"), "n": e["n"], "kind": "up" if up else "down"}
        edges.append(edge)
        if up:
            upward.append({"from": edge["from"], "to": edge["to"], "text": edge["label"]})
    groups = [{"id": band, "label": label, "column": column, "cols": cols} for band, label, column, cols in BANDS]
    result = columns_layout(nodes, edges, groups)
    groups = [{"id": g["id"], "label": g["label"]} for g in groups]
    view = {
        "id": "packages", "tab": "Packages", "noun": "package",
        "blurb": "Packages of the server module under dev.dertyp, arranged by layer from the entry points on the left to the other Gradle modules on the right, and linked by import statements. An import that points back to an earlier layer is drawn in the warning colour. Thicker lines carry more imports. Wildcard imports of a package that exists in two modules are counted for both.",
        "out": "Imports", "inn": "Imported by", "labels": False, "quiet": True,
        "groups": groups,
        "styles": [{"kind": "down", "dash": "", "label": "imports from the same or a lower layer"}, {"kind": "up", "dash": "", "label": "upward import, against the layer order", "tone": "warn"}],
        "lists": [{"title": "Upward imports", "items": upward}],
        "notes": [],
    }
    view.update(result)
    return view


def class_group(component):
    name, package = component["id"], component["pkg"]
    if name in CLASS_OVERRIDES:
        return CLASS_OVERRIDES[name]
    if name.startswith("Rpc") or name.endswith("RpcService") or package == "(root)":
        return "entry"
    if package == "services.schedule":
        return "schedule"
    module = component["module"]
    if module:
        return KOIN_GROUPS.get(module, module)
    for prefix, group in PACKAGE_GROUPS:
        if package == prefix or package.startswith(prefix + "."):
            return group
    return "system"


def class_kind(component):
    name = component["id"]
    if component["pkg"] == "services.schedule" and name.endswith("Worker"):
        return "worker"
    if name.startswith("Rpc") or name.endswith("RpcService"):
        return "RPC adapter"
    if component["subscriber"]:
        return "hook subscriber"
    if "Importer" in name or name.endswith("Indexer"):
        return "importer"
    if name.endswith("Service"):
        return "service"
    if component["kind"] == "interface":
        return "interface"
    return "component"


def classes_view(code):
    nodes = []
    known = {group for group, _ in CLASS_GROUPS}
    for c in code["components"]:
        if c["id"] in SKIPPED_CLASSES:
            continue
        group = class_group(c)
        if group not in known:
            raise SystemExit("no class group %s for %s" % (group, c["id"]))
        kind = class_kind(c)
        meta = [["Kind", kind], ["File", c["path"].replace("server/src/main/kotlin/dev/dertyp/", "")], ["Lines", str(c["lines"])]]
        if c["module"]:
            meta.append(["Koin module", c["module"] + "Module"])
        if c["rpc"]:
            meta.append(["Implements", ", ".join(c["rpc"])])
        nodes.append({"id": c["id"], "label": c["id"], "sub": kind, "group": group, "meta": meta, "src": c["path"]})
    ids = {n["id"] for n in nodes}
    edges = [{"from": e["from"], "to": e["to"], "label": "injects", "kind": "inject"} for e in sorted(code["componentEdges"], key=lambda e: (e["from"], e["to"])) if e["from"] in ids and e["to"] in ids]
    used = {e["from"] for e in edges} | {e["to"] for e in edges}
    nodes = [n for n in nodes if n["id"] in used]
    groups = [{"id": group, "label": label} for group, label in CLASS_GROUPS]
    result = blocks_layout(nodes, edges, groups, ratio=1.7)
    kinds = Counter(n["sub"] for n in nodes)
    view = {
        "id": "classes", "tab": "Classes", "noun": "class",
        "blurb": "Server classes linked by dependency injection (Koin inject and get calls plus constructor parameters), grouped by the Koin module that binds them. Links inside a domain are drawn, links between domains stay faint until you select a class. Interfaces with one implementation are shown as that implementation. ServiceRegistrar is left out because it only wires everything.",
        "out": "Injects", "inn": "Injected into", "far": "faint", "labels": False,
        "groups": groups,
        "styles": [{"kind": "inject", "dash": "", "label": "depends on"}],
        "notes": [{"title": "Kinds", "text": ", ".join("%d %s" % (count, kind) for kind, count in sorted(kinds.items(), key=lambda item: (-item[1], item[0]))) + "."}],
    }
    view.update(result)
    return view
