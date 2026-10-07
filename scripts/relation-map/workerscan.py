import re

SCHEDULE_DIR = "server/src/main/kotlin/dev/dertyp/services/schedule"
TASK_KEYS = "common-rpc/src/commonMain/kotlin/dev/dertyp/data/TaskKeys.kt"

ANNOTATION = re.compile(r"^@WorkerTask\((.*?)\)\s*\n\s*(?:open\s+|internal\s+)?class\s+(\w+)\s*:\s*(\w+)", re.M | re.S)
CONSTANT = re.compile(r"const\s+val\s+(\w+)\s*=\s*\"([^\"]*)\"")
INJECT = re.compile(r"by\s+inject<([\w.]+)(?:<[^>]*>)?>\s*\(")
NAMED = re.compile(r"^(\w+)\s*=\s*(.+)$", re.S)
POSITIONAL = ("key", "name", "enabled", "cron", "intervalSeconds", "afterTask")


def read_task_keys(root):
    path = root / TASK_KEYS
    if not path.is_file():
        raise SystemExit(f"relation-map: {TASK_KEYS} not found")
    return {m.group(1): m.group(2) for m in CONSTANT.finditer(path.read_text())}


def split_arguments(text):
    parts = []
    current = []
    quoted = False
    for char in text:
        if char == "\"":
            quoted = not quoted
        if char == "," and not quoted:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


def resolve(value, keys, where):
    value = value.strip()
    if value.startswith("\"") and value.endswith("\""):
        return value[1:-1]
    if value in ("true", "false"):
        return value == "true"
    if re.fullmatch(r"\d+L?", value):
        return int(value.rstrip("L"))
    name = value.split(".")[-1]
    if value.startswith("TaskKeys.") and name in keys:
        return keys[name]
    raise SystemExit(f"relation-map: cannot resolve WorkerTask argument {value!r} in {where}")


def parse_annotation(text, keys, where):
    values = {"enabled": True, "cron": "", "intervalSeconds": 0, "afterTask": ""}
    position = 0
    for part in split_arguments(text):
        named = NAMED.match(part)
        if named and named.group(1) in POSITIONAL:
            values[named.group(1)] = resolve(named.group(2), keys, where)
        else:
            values[POSITIONAL[position]] = resolve(part, keys, where)
            position += 1
    for required in ("key", "name"):
        if required not in values:
            raise SystemExit(f"relation-map: WorkerTask without {required} in {where}")
    return values


def trigger_of(values, where):
    triggers = []
    if values["cron"]:
        triggers.append({"kind": "cron", "value": values["cron"]})
    if values["intervalSeconds"]:
        triggers.append({"kind": "interval", "value": str(values["intervalSeconds"])})
    if values["afterTask"]:
        triggers.append({"kind": "after", "value": values["afterTask"]})
    if len(triggers) > 1:
        raise SystemExit(f"relation-map: more than one default trigger in {where}")
    return triggers[0] if triggers else {"kind": "manual", "value": ""}


def scan_workers(root):
    directory = root / SCHEDULE_DIR
    if not directory.is_dir():
        raise SystemExit(f"relation-map: {SCHEDULE_DIR} not found")
    keys = read_task_keys(root)
    workers = []
    for path in sorted(directory.glob("*.kt")):
        source = path.read_text()
        relative = path.relative_to(root).as_posix()
        for match in ANNOTATION.finditer(source):
            line = source.count("\n", 0, match.start()) + 1
            where = f"{relative}:{line}"
            values = parse_annotation(match.group(1), keys, where)
            workers.append({
                "key": values["key"],
                "name": values["name"],
                "enabled": values["enabled"],
                "trigger": trigger_of(values, where),
                "class": match.group(2),
                "base": match.group(3),
                "src": where,
                "injects": sorted({m.group(1).split(".")[-1] for m in INJECT.finditer(source)}),
            })
    workers.sort(key=lambda worker: worker["key"])
    seen = set()
    for worker in workers:
        if worker["key"] in seen:
            raise SystemExit(f"relation-map: duplicate worker key {worker['key']}")
        seen.add(worker["key"])
    return workers
