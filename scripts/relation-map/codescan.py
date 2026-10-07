import re
from collections import Counter, defaultdict

MODULES = {
    "server": "server/src/main/kotlin",
    "common-rpc": "common-rpc/src",
    "common-rpc:compiler": "common-rpc/compiler/src",
    "common-rpc:doc-compiler": "common-rpc/doc-compiler/src",
    "common-rpc:rest-compiler": "common-rpc/rest-compiler/src",
    "proxy": "proxy/src",
    "common-proxy": "common-proxy/src",
    "credential-server": "credential-server/src",
    "common-credentials": "common-credentials/src",
    "listen-backup": "listen-backup/src",
    "common-listen-backup": "common-listen-backup/src",
    "plugin-api": "plugin-api/src",
    "mock-server": "mock-server/src",
}

DECL = re.compile(r'^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|abstract|open|data|sealed|value|inline|enum|annotation|fun|expect|actual|suspend|const|override|tailrec|operator|infix|external)\s+)*(class|object|interface|typealias|fun|val|var)\s+(?:<[^>]+>\s+)?(?:[\w.<>?, ]+\.)?(\w+)', re.M)
CLASS = re.compile(r'^(?:(?:public|internal|private|abstract|open|data|sealed|value|enum|annotation)\s+)*(class|object|interface)\s+(\w+)', re.M)
DEPENDENCY_SUFFIX = re.compile(r'(Service|Worker|Recorder|Publisher|Client|Manager|Resolver|Provider|Registry|Factory|Broker|Notifier|Library|Indexer|Store)$')


def read_sources(root):
    files = []
    for module, relative in MODULES.items():
        base = root / relative
        for path in sorted(base.rglob("*.kt")):
            directory = str(path.parent)
            if "/build" in directory or "Test" in directory.replace(str(base), "") or "/test/" in directory + "/":
                continue
            source = path.read_text(errors="replace")
            package = re.search(r"^package\s+([\w.]+)", source, re.M)
            files.append({"mod": module, "path": str(path.relative_to(root)), "pkg": package.group(1) if package else "", "src": source})
    return files


def short(package):
    return package.replace("dev.dertyp", "").lstrip(".") or "(root)"


def scan_code(root):
    files = read_sources(root)

    declarations = defaultdict(set)
    package_modules = defaultdict(set)
    module_files = Counter()
    module_lines = Counter()
    for f in files:
        module_files[f["mod"]] += 1
        module_lines[f["mod"]] += f["src"].count("\n")
        package_modules[f["pkg"]].add(f["mod"])
        for match in DECL.finditer(f["src"]):
            declarations[f["pkg"] + "." + match.group(2)].add(f["mod"])

    package_files = Counter()
    package_edges = Counter()
    module_edges = Counter()
    for f in files:
        if f["mod"] == "server":
            package_files[short(f["pkg"])] += 1
        for match in re.finditer(r"^import\s+(dev\.dertyp[\w.]*?)(\.\*)?(?:\s+as\s+\w+)?\s*$", f["src"], re.M):
            qualified, star = match.group(1), match.group(2)
            if star:
                package = qualified
                modules = package_modules.get(package, set())
            else:
                modules = declarations.get(qualified, set())
                package = qualified.rsplit(".", 1)[0]
                if not modules:
                    parts = qualified.split(".")
                    for i in range(len(parts) - 1, 1, -1):
                        candidate = ".".join(parts[:i])
                        if declarations.get(candidate):
                            modules = declarations[candidate]
                            package = ".".join(parts[:i - 1])
                            break
            if len(modules) > 1 and f["mod"] in modules and not star:
                modules = {f["mod"]}
            for target in sorted(modules):
                if target != f["mod"]:
                    module_edges[(f["mod"], target)] += 1
                if f["mod"] == "server":
                    source_node = short(f["pkg"])
                    target_node = short(package) if target == "server" else "@" + target
                    if source_node != target_node:
                        package_edges[(source_node, target_node)] += 1

    server = [f for f in files if f["mod"] == "server"]
    class_file = {}
    kind = {}
    supers = defaultdict(set)
    for f in server:
        for match in CLASS.finditer(f["src"]):
            class_file.setdefault(match.group(2), f)
            kind.setdefault(match.group(2), match.group(1))
        for match in re.finditer(r"^(?:(?:abstract|open|internal|private|data|sealed)\s+)*(?:class|object)\s+(\w+)[^{=]*?\)?\s*:\s*([^{]+)\{", f["src"], re.M | re.S):
            for sup in re.findall(r"\b([A-Z]\w+)", match.group(2)):
                supers[sup].add(match.group(1))
    rpc_interfaces = {m.group(2) for f in files if f["mod"] == "common-rpc" for m in CLASS.finditer(f["src"]) if m.group(1) == "interface"}

    def resolve(type_name):
        type_name = type_name.strip().rstrip("?").split("<")[0].split(".")[-1]
        if type_name in class_file and kind[type_name] != "interface":
            return type_name
        implementations = sorted(c for c in supers.get(type_name, ()) if c in class_file)
        if len(implementations) == 1:
            return implementations[0]
        return type_name if type_name in class_file else None

    dependencies = defaultdict(Counter)
    for f in server:
        source = f["src"]
        marks = [(m.start(), m.group(2)) for m in CLASS.finditer(source)]
        if not marks:
            continue

        def owner(position, marks=marks):
            current = marks[0][1]
            for start, name in marks:
                if start <= position:
                    current = name
            return current

        for match in re.finditer(r"(?:by\s+inject|inject|get)<([^>()]+(?:<[^>]*>)?)>\s*\(", source):
            target = resolve(match.group(1))
            current = owner(match.start())
            if target and target != current:
                dependencies[current][target] += 1
        for start, name in marks:
            constructor = re.match(r"(?:class|object)\s+\w+\s*(?:@\w+\s+)?(?:constructor\s*)?\(([^{]*?)\)\s*[:{\n]", source[source.find("class " + name, start) if "class " + name in source[start:start + 200] else start:], re.S)
            if not constructor:
                continue
            for param in re.finditer(r"(?:val|var)\s+\w+\s*:\s*([A-Z][\w.]*)", constructor.group(1)):
                target = resolve(param.group(1))
                if target and target != name and DEPENDENCY_SUFFIX.search(target):
                    dependencies[name][target] += 1

    koin = {}
    subscribers = set()
    for f in server:
        if not f["path"].endswith("Module.kt"):
            continue
        module = f["path"].rsplit("/", 1)[1][:-len("Module.kt")].lower()
        for line in f["src"].split("\n"):
            match = re.search(r"(?:singleOf|factoryOf)\(::(\w+)\)", line) or re.search(r"(?:single|factory)(?:<[\w.]+>)?\s*\{\s*(\w+)\(", line)
            if not match:
                continue
            koin.setdefault(match.group(1), module)
            if "bind<HookSubscriber>" in line:
                subscribers.add(match.group(1))
    parents = defaultdict(set)
    for sup, implementors in supers.items():
        for implementor in implementors:
            parents[implementor].add(sup)

    nodes = set()
    for source_name, counter in dependencies.items():
        nodes.add(source_name)
        nodes.update(counter)
    components = []
    for name in sorted(nodes):
        f = class_file[name]
        components.append({"id": name, "pkg": short(f["pkg"]), "path": f["path"], "kind": kind[name], "lines": f["src"].count("\n"),
                           "module": koin.get(name), "subscriber": name in subscribers or bool(parents[name] & {"HookSubscriber", "EntityWriteSubscriber"}),
                           "rpc": sorted(s for s, implementors in supers.items() if name in implementors and s in rpc_interfaces)})
    component_edges = [{"from": a, "to": b, "n": n} for a, counter in dependencies.items() for b, n in counter.items()]

    return {
        "modules": [{"id": m, "files": module_files[m], "lines": module_lines[m]} for m in MODULES],
        "moduleImports": [{"from": a, "to": b, "n": n} for (a, b), n in module_edges.items()],
        "packages": [{"id": p, "files": n} for p, n in package_files.items()],
        "packageEdges": [{"from": a, "to": b, "n": n} for (a, b), n in package_edges.items()],
        "components": components,
        "componentEdges": component_edges,
    }
