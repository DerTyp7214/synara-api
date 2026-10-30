#!/usr/bin/env python3
"""Timeline of every Flyway and custom database migration in this repository's history.

See USAGE below, or run with --help.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TITLE = "Synara"

USAGE = """Reads the git history with read-only git commands and writes docs/MIGRATION_TIMELINE.html,
one self-contained interactive page (data inlined, no network requests) to open locally in a browser.
Lists every Flyway migration (Kotlin Java migrations, the frozen base and the SQL pairs) and every
custom migration that ever existed, with the commit that added it and what happened to it later.
-o writes the page elsewhere, --json also saves the collected data.
Unchanged history gives a byte-identical page. Standard library only."""

FLYWAY_RE = re.compile(r"(?:^|/)src/main/.*migrations/(?:.*/)?([VB])(\d+(?:_\d+)*)__(\w+)\.(kt|sql)$")
CUSTOM_RE = re.compile(r"(?:^|/)src/main/.*migrations/custom/(\w+)\.kt$")
MIGRATION_BASE = "MigrationBase.kt"

# --------------------------------------------------------------------------
# git helpers (read-only)
# --------------------------------------------------------------------------

GIT_ENV = dict(os.environ, GIT_OPTIONAL_LOCKS="0", LC_ALL="C", GIT_PAGER="cat")


def git(repo: Path, *args: str, stdin: bytes | None = None, check: bool = True) -> bytes:
    cmd = ["git", "--no-optional-locks", "-c", "core.quotePath=false", "-C", str(repo), *args]
    p = subprocess.run(cmd, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=GIT_ENV)
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}\n{p.stderr.decode(errors='replace')}")
    return p.stdout if p.returncode == 0 else b""


def read_blobs(repo: Path, specs: list[str]) -> dict[str, str]:
    """Contents of "<commit>:<path>" objects, read in one cat-file --batch call."""
    specs = sorted(set(specs))
    if not specs:
        return {}
    out = git(repo, "cat-file", "--batch", stdin="".join(s + "\n" for s in specs).encode())
    found, pos = {}, 0
    for spec in specs:
        nl = out.index(b"\n", pos)
        header = out[pos:nl].split()
        if len(header) == 3 and header[1] == b"blob":
            size = int(header[2])
            found[spec] = out[nl + 1: nl + 1 + size].decode("utf-8", "replace")
            pos = nl + 1 + size + 1
        else:                                  # "<spec> missing"
            pos = nl + 1
    return found


def remote_web_url(repo: Path) -> str | None:
    """https://host/owner/repo for the origin remote, from scp-like, ssh:// or https URLs."""
    raw = git(repo, "remote", "get-url", "origin", check=False).decode().strip()
    m = (re.match(r"^(?:ssh://)?[\w.-]+@([\w.-]+)(?::\d+)?[:/]+([\w.-]+)/([\w.-]+?)(?:\.git)?/?$", raw)
         or re.match(r"^https?://(?:[^@/]+@)?([\w.-]+)(?::\d+)?/+([\w.-]+)/([\w.-]+?)(?:\.git)?/?$", raw))
    return f"https://{m.group(1)}/{m.group(2)}/{m.group(3)}" if m else None


# --------------------------------------------------------------------------
# Blob analysis
# --------------------------------------------------------------------------

SQL_TABLE_RES = (
    re.compile(r"\bCREATE\s+(?:VIRTUAL\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?((?:\"?\w+\"?\.)?\"?\w+\"?)", re.I),
    re.compile(r"\bALTER\s+TABLE\s+(?:ONLY\s+)?(?:IF\s+EXISTS\s+)?((?:\"?\w+\"?\.)?\"?\w+\"?)", re.I),
    re.compile(r"\bDROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?((?:\"?\w+\"?\.)?\"?\w+\"?)", re.I),
    re.compile(r"\bCREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:CONCURRENTLY\s+)?(?:IF\s+NOT\s+EXISTS\s+)?\"?\w+\"?\s+ON\s+(?:ONLY\s+)?((?:\"?\w+\"?\.)?\"?\w+\"?)", re.I),
)
KOTLIN_TABLE_RE = re.compile(r"\b([A-Z]\w*Table)\b")
TABLE_DECL_RE = re.compile(r'object\s+(\w+Table)\s*:\s*[\w<>, ]*?\(\s*(?:name\s*=\s*)?"(\w+)"')
EXPOSED_BASE_TABLES = {"IdTable", "UUIDTable", "IntIdTable", "LongIdTable", "UIntIdTable", "ULongIdTable", "CompositeIdTable"}
SQL_WORDS = {"if", "only", "not", "exists"}


def table_names(repo: Path) -> dict[str, str]:
    """Kotlin table object name -> SQL table name, from the declarations at HEAD."""
    out = git(repo, "grep", "-h", "-E", "object [A-Za-z0-9_]+Table", "HEAD", "--", "*.kt", check=False)
    return dict(TABLE_DECL_RE.findall(out.decode("utf-8", "replace")))


def tables_in(text: str, kotlin: bool, names: dict[str, str]) -> set[str]:
    """SQL table names a migration creates, alters, drops or indexes; Kotlin table objects become their SQL name."""
    found = set()
    for rx in SQL_TABLE_RES:
        for m in rx.finditer(text):
            name = m.group(1).replace('"', "").rsplit(".", 1)[-1]
            if name.lower() not in SQL_WORDS:
                found.add(name)
    if kotlin:
        local = dict(names, **dict(TABLE_DECL_RE.findall(text)))
        classes = set(re.findall(r"\bclass\s+(\w+)", text)) | EXPOSED_BASE_TABLES
        found.update(local.get(t, t) for t in KOTLIN_TABLE_RE.findall(text) if t not in classes)
    return found


def unique_tables(tables: set[str]) -> list[str]:
    """One spelling per table: PostgreSQL folds unquoted names to lower case, so keep the mixed-case one."""
    best = {}
    for t in tables:
        k = t.lower()
        if k not in best or (t != k, t) > (best[k] != k, best[k]):
            best[k] = t
    return [best[k] for k in sorted(best)]


def custom_version(text: str) -> str | None:
    m = re.search(r'@Migration\(\s*"([^"]+)"\s*\)', text)
    return m.group(1) if m else None


def custom_label(text: str) -> str | None:
    m = re.search(r'logTask\(\s*"((?:[^"\\]|\\.)*)"', text)
    return m.group(1) if m else None


def humanize(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", name)


def version_key(v: str) -> tuple:
    return tuple(int(p) if p.isdigit() else 0 for p in re.split(r"[._]", v))


def first_sentence(text: str, limit: int = 180) -> str:
    """Head of a commit subject: up to the first sentence break outside brackets, cut at a word if still long.

    A colon only ends it after a real clause, so a short "Topic: ..." prefix stays with its sentence.
    """
    depth, end = 0, len(text)
    for i, ch in enumerate(text):
        depth += ch in "([{"
        depth -= ch in ")]}" and depth > 0
        if depth == 0 and ch in ".;:" and text[i + 1:i + 2] == " " and (ch != ":" or i >= 40):
            end = i
            break
    head = text[:end]
    if len(head) > limit:
        head = head[:limit].rsplit(" ", 1)[0].rstrip(",:") + "\u2026"
    return head


# --------------------------------------------------------------------------
# Collection
# --------------------------------------------------------------------------

RS, US = "\x1e", "\x1f"
LOG_FORMAT = f"{RS}%H{US}%ad{US}%B{US}"


def read_history(repo: Path) -> list[dict]:
    """Commits touching a migrations folder, oldest first, with their file changes (renames detected)."""
    out = git(repo, "log", "HEAD", "--reverse", "-M", "--name-status", "--date=short",
              f"--format={LOG_FORMAT}", "--", "*migrations/*")
    commits = []
    for rec in out.decode("utf-8", "replace").split(RS)[1:]:
        sha, date, message, rest = (rec.split(US, 3) + [""] * 4)[:4]
        changes = []
        for line in rest.splitlines():
            cols = line.split("\t")
            if len(cols) == 2 and cols[0] in ("A", "D", "M"):
                changes.append((cols[0], None if cols[0] == "A" else cols[1], None if cols[0] == "D" else cols[1]))
            elif len(cols) == 3 and cols[0][:1] in ("R", "C"):
                changes.append((cols[0][0], cols[1], cols[2]))
        if changes:
            commits.append({"sha": sha, "date": date, "message": message.strip(), "changes": changes})
    return commits


def classify(path: str | None) -> tuple[str, str, str] | None:
    """(kind, key, name) for a migration path, or None. kind is flyway, base or custom."""
    if not path:
        return None
    m = FLYWAY_RE.search(path)
    if m:
        kind = "base" if m.group(1) == "B" else "flyway"
        return kind, m.group(1) + m.group(2), m.group(3)
    m = CUSTOM_RE.search(path)
    if m:
        return "custom", m.group(1), m.group(1)
    return None


def collect(repo: Path) -> dict:
    commits = read_history(repo)
    names = table_names(repo)
    entries: dict[tuple[str, str], dict] = {}
    commit_index: dict[str, int] = {}
    blob_wants: list[str] = []

    def cidx(c: dict) -> int:
        if c["sha"] not in commit_index:
            commit_index[c["sha"]] = len(commit_index)
        return commit_index[c["sha"]]

    def entry(kind: str, key: str, name: str, c: dict) -> dict:
        ident = ("custom" if kind == "custom" else "flyway", key)
        e = entries.get(ident)
        if e is None:
            e = entries[ident] = {
                "kind": kind, "key": key, "name": name, "names": [name], "added": cidx(c),
                "paths": {}, "touched": {}, "gone": None, "events": [], "addSpecs": [],
            }
        elif name not in e["names"]:
            e["names"].append(name)
            e["name"] = name
        return e

    for c in commits:
        is_base_commit = any(ch[0] == "A" and (classify(ch[2]) or ("",))[0] == "base" for ch in c["changes"])
        removals = []
        for status, old, new in c["changes"]:
            o, n = classify(old), classify(new)
            if n and status in ("A", "R", "C", "M"):
                e = entry(n[0], n[1], n[2], c)
                e["paths"][new] = c["sha"]
                e["touched"][new] = c["sha"]
                if status != "M" and (not o or o[:2] != n[:2]):
                    e["addSpecs"].append(f"{c['sha']}:{new}")
                if o and o[:2] != n[:2]:
                    e["renamedFrom"] = [o[1], o[2], cidx(c)]
            if o and status in ("D", "R"):
                removals.append((o, old, n))
        for o, old, n in removals:
            e = entries.get(("custom" if o[0] == "custom" else "flyway", o[1]))
            if e is None:
                continue
            e["paths"].pop(old, None)
            if n and n[:2] != o[:2]:
                e["renamedTo"] = [n[1], n[2], cidx(c)]
            if not e["paths"] and e["gone"] is None:
                e["gone"] = [cidx(c), "renamed" if "renamedTo" in e and o[0] == "custom" else
                             "folded" if is_base_commit and "renamedTo" not in e else "removed"]
            elif n is None and e["paths"]:
                e["events"].append([cidx(c), f"{o[1]}__{o[2]} deleted" if o[0] != "custom" else f"{o[2]} deleted"])

    for e in entries.values():
        e["lastSpecs"] = [f"{sha}:{p}" for p, sha in sorted(e["touched"].items())]
        blob_wants.extend(e["addSpecs"] + e["lastSpecs"])
    blobs = read_blobs(repo, blob_wants)
    shas = list(commit_index)

    result = []
    for (_, key), e in entries.items():
        texts_add = [blobs.get(s, "") for s in e["addSpecs"]]
        texts_last = [blobs.get(s, "") for s in e["lastSpecs"]]
        tables = set()
        for spec, text in zip(e["addSpecs"] + e["lastSpecs"], texts_add + texts_last):
            tables |= tables_in(text, spec.endswith(".kt"), names)
        item = {
            "kind": e["kind"], "key": key, "name": e["name"], "added": e["added"],
            "status": e["gone"][1] if e["gone"] else "current",
            "gone": e["gone"][0] if e["gone"] else None,
            "tables": unique_tables(tables),
            "files": sorted({re.sub(r"^.*/(postgres|sqlite)/.*$", r"\1", p.split(":", 1)[1])
                             for p in e["addSpecs"] if p.endswith(".sql")}),
        }
        if len(e["names"]) > 1:
            item["names"] = e["names"]
        if e["events"]:
            item["events"] = e["events"]
        for k in ("renamedFrom", "renamedTo"):
            if k in e:
                item[k] = e[k]
        if e["kind"] == "custom":
            version = next((v for v in map(custom_version, texts_add) if v), None) or \
                next((v for v in map(custom_version, texts_last) if v), None)
            label = next((v for v in map(custom_label, texts_add) if v), None) or \
                next((v for v in map(custom_label, texts_last) if v), None)
            if version is None:
                continue
            item["version"] = version
            item["label"] = label or humanize(key)
        else:
            item["version"] = key[1:].replace("_", ".")
        result.append(item)

    kind_order = {"base": 0, "flyway": 1, "custom": 2}
    result.sort(key=lambda x: (x["added"], kind_order[x["kind"]], version_key(x["version"]), x["key"]))

    used = sorted({x["added"] for x in result} | {x["gone"] for x in result if x["gone"] is not None}
                  | {x[k][2] for x in result for k in ("renamedFrom", "renamedTo") if k in x}
                  | {ev[0] for x in result for ev in x.get("events", [])})
    by_sha = {c["sha"]: c for c in commits}
    remap = {old: new for new, old in enumerate(used)}
    for x in result:
        x["added"] = remap[x["added"]]
        if x["gone"] is not None:
            x["gone"] = remap[x["gone"]]
        for k in ("renamedFrom", "renamedTo"):
            if k in x:
                x[k][2] = remap[x[k][2]]
        for ev in x.get("events", []):
            ev[0] = remap[ev[0]]
    commit_list = []
    for old in used:
        c = by_sha[shas[old]]
        subject = c["message"].split("\n", 1)[0].strip()
        commit_list.append({
            "sha": c["sha"], "date": c["date"], "summary": first_sentence(subject),
            "message": c["message"],
        })
    return {"entries": result, "commits": commit_list}


def migration_base(repo: Path) -> dict:
    """VERSION, LAST_CUSTOM_MIGRATION and STEPPING_STONE_IMAGE from MigrationBase.kt at HEAD."""
    listing = git(repo, "ls-tree", "-r", "--name-only", "HEAD").decode("utf-8", "replace").splitlines()
    path = next((p for p in sorted(listing) if p.endswith("/" + MIGRATION_BASE) and "/src/main/" in p), None)
    if not path:
        return {}
    text = git(repo, "show", f"HEAD:{path}").decode("utf-8", "replace")
    found = {"file": path}
    for const, key in (("VERSION", "version"), ("LAST_CUSTOM_MIGRATION", "lastCustom"), ("STEPPING_STONE_IMAGE", "steppingStone")):
        m = re.search(rf'\bconst\s+val\s+{const}\s*=\s*"([^"]*)"', text)
        if m:
            found[key] = m.group(1)
    return found


def milestones(data: dict, base: dict) -> list[dict]:
    """Points where the migration system changed, found from the data rather than fixed commits."""
    entries = data["entries"]
    found = []
    first_flyway = next((e for e in entries if e["kind"] in ("flyway", "base")), None)
    if first_flyway:
        found.append({"id": "flyway", "commit": first_flyway["added"], "title": "Flyway introduced",
                      "text": f"The schema is versioned by Flyway, starting with V{first_flyway['version'].replace('.', '_')} {first_flyway['name']}."})
    first_custom = next((e for e in entries if e["kind"] == "custom"), None)
    if first_custom:
        found.append({"id": "custom", "commit": first_custom["added"], "title": "Custom migrations introduced",
                      "text": f"Data migrations written in Kotlin get their own version sequence and are recorded by class name "
                              f"once they ran. The first is {first_custom['version']} {first_custom['name']}."})
    base_entry = next((e for e in entries if e["kind"] == "base"), None)
    if base_entry:
        c = base_entry["added"]
        folded_f = sum(1 for e in entries if e["kind"] == "flyway" and e["status"] == "folded" and e["gone"] == c)
        folded_c = sum(1 for e in entries if e["kind"] == "custom" and e["status"] == "folded" and e["gone"] == c)
        text = (f"{folded_f} Flyway migrations and {folded_c} custom migrations are folded into "
                f"{base_entry['key']}__{base_entry['name']}.sql, which creates the schema of an empty database at version {base_entry['version']}.")
        if base.get("steppingStone") and base.get("lastCustom"):
            text += (f" An older database first runs the image {base['steppingStone']} until the custom migration "
                     f"{base['lastCustom']} is recorded, then updates past the base.")
        found.append({"id": "base", "commit": c, "title": "Base frozen", "text": text,
                      "steppingStone": base.get("steppingStone"), "lastCustom": base.get("lastCustom")})
    return found


def render(data: dict, title: str) -> str:
    payload = json.dumps(data, separators=(",", ":"), ensure_ascii=False)
    payload = payload.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    safe_title = (title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
    return TEMPLATE.replace("__TITLE__", safe_title).replace("__DATA__", payload)


def parse_arguments():
    parser = argparse.ArgumentParser(description=USAGE, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("repo", nargs="?", type=Path, default=Path("."), help="git repository to read (default: .)")
    parser.add_argument("-o", "--output", type=Path, help="page to write (default: docs/MIGRATION_TIMELINE.html in the repository)")
    parser.add_argument("--title", default=TITLE, help=f"page title (default: {TITLE})")
    parser.add_argument("--json", type=Path, dest="json_output", help="also write the collected data to this file")
    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    started = time.perf_counter()
    repo = args.repo.resolve()
    if not git(repo, "rev-parse", "--verify", "--quiet", "HEAD^{commit}", check=False).strip():
        print(f"{repo} is not a git repository with commits.", file=sys.stderr)
        return 1
    top = Path(git(repo, "rev-parse", "--show-toplevel").decode().strip())
    data = collect(top)
    if not data["entries"]:
        print("No migrations found in the history.", file=sys.stderr)
        return 1
    base = migration_base(top)
    data["milestones"] = milestones(data, base)
    data["base"] = base
    data["web"] = remote_web_url(top)
    head = git(top, "log", "-1", "--date=short", "--format=%H%x1f%ad", "HEAD").decode().strip().split(US)
    data["head"] = {"sha": head[0], "date": head[1]}
    data["title"] = args.title

    kinds = {k: sum(1 for e in data["entries"] if e["kind"] == k) for k in ("flyway", "base", "custom")}
    print(f"  {kinds['flyway']} Flyway migrations, {kinds['base']} base, {kinds['custom']} custom migrations, "
          f"{len(data['commits'])} commits")
    output = args.output or top / "docs" / "MIGRATION_TIMELINE.html"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(data, args.title), encoding="utf-8", newline="\n")
    print(f"Wrote {output} ({output.stat().st_size / 1024:,.0f} KB)")
    if args.json_output:
        args.json_output.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Done in {time.perf_counter() - started:.2f}s")
    return 0


TEMPLATE = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__ migration timeline</title>
<style>
:root {
  color-scheme: light;
  --bg: #fafbfc; --raised: #ffffff; --ink: #14171c; --ink2: #4a515c; --muted: #6b727d;
  --grid: #e7eaee; --axis: #c5cad2; --border: rgba(20,23,28,.13); --wash: rgba(20,23,28,.05);
  --s0: #2a78d6; --s1: #eb6834; --s2: #1baf7a; --s3: #eda100; --s4: #e87ba4; --s5: #008300;
  --s6: #4a3aa7; --s7: #e34948; --other: #9aa1ab; --all: #3d4450;
  --shadow: 0 6px 24px rgba(20,23,28,.12), 0 1px 3px rgba(20,23,28,.10);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --bg: #14161a; --raised: #1f2228; --ink: #f3f5f7; --ink2: #b9c0ca; --muted: #8b929d;
    --grid: #262a31; --axis: #3d424b; --border: rgba(255,255,255,.14); --wash: rgba(255,255,255,.07);
    --s0: #3987e5; --s1: #d95926; --s2: #199e70; --s3: #c98500; --s4: #d55181; --s5: #008300;
    --s6: #9085e9; --s7: #e66767; --other: #5d646f; --all: #c9cfd8;
    --shadow: 0 8px 28px rgba(0,0,0,.5), 0 1px 3px rgba(0,0,0,.4);
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --bg: #14161a; --raised: #1f2228; --ink: #f3f5f7; --ink2: #b9c0ca; --muted: #8b929d;
  --grid: #262a31; --axis: #3d424b; --border: rgba(255,255,255,.14); --wash: rgba(255,255,255,.07);
  --s0: #3987e5; --s1: #d95926; --s2: #199e70; --s3: #c98500; --s4: #d55181; --s5: #008300;
  --s6: #9085e9; --s7: #e66767; --other: #5d646f; --all: #c9cfd8;
  --shadow: 0 8px 28px rgba(0,0,0,.5), 0 1px 3px rgba(0,0,0,.4);
}
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body {
  margin: 0; background: var(--bg); color: var(--ink);
  font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}
.wrap { max-width: 980px; margin: 0 auto; padding: 0 28px; }
button, input { font: inherit; color: inherit; }
button { cursor: pointer; }
a { color: inherit; }
code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .9em; }
:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; border-radius: 4px; }

/* ---------- header ---------- */
.top { padding: 44px 0 14px; display: flex; gap: 16px; align-items: flex-start; justify-content: space-between; }
h1 { margin: 0; font-size: clamp(30px, 5.4vw, 52px); line-height: 1.04; font-weight: 680; letter-spacing: -0.028em; }
#lede { margin: 12px 0 0; color: var(--ink2); font-size: 16px; max-width: 62ch; }
#theme {
  flex: none; margin-top: 8px; background: none; border: 1px solid var(--border); border-radius: 999px;
  padding: 5px 13px; font-size: 13px; color: var(--ink2); white-space: nowrap;
}
#theme:hover { background: var(--wash); }

/* ---------- figures ---------- */
.stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 26px 28px; padding: 26px 0 28px; border-top: 1px solid var(--grid); }
.stat .v { font-size: clamp(22px, 2.9vw, 31px); font-weight: 650; letter-spacing: -0.02em; line-height: 1.1; white-space: nowrap; }
.stat .l { color: var(--ink2); font-size: 13.5px; margin-top: 5px; }
.stat .s { color: var(--muted); font-size: 12.5px; }

/* ---------- filter bar ---------- */
.filterbar { position: sticky; top: 0; z-index: 20; background: color-mix(in srgb, var(--bg) 88%, transparent);
  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); border-bottom: 1px solid var(--grid); border-top: 1px solid var(--grid); }
.filters { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: center; padding: 10px 0; }
.chips { display: flex; gap: 6px; flex-wrap: wrap; }
.chips button {
  display: inline-flex; align-items: center; gap: 7px; background: none;
  border: 1px solid var(--border); border-radius: 999px; padding: 4px 12px 4px 10px; font-size: 13.5px; color: var(--ink2);
}
.chips button:hover { background: var(--wash); }
.chips button[aria-pressed="true"] { background: var(--ink); color: var(--bg); border-color: var(--ink); font-weight: 600; }
.chips button .n { font-variant-numeric: tabular-nums; opacity: .7; font-weight: 400; font-size: 12.5px; }
.chips button[aria-pressed="true"] .dot { box-shadow: 0 0 0 1.5px var(--bg); }
.sep { width: 1px; align-self: stretch; background: var(--grid); }
#q { flex: 1 1 200px; min-width: 160px; background: var(--raised); border: 1px solid var(--border); border-radius: 999px;
  padding: 5px 14px; font-size: 14px; }
#q::placeholder { color: var(--muted); }
#count { color: var(--muted); font-size: 13px; font-variant-numeric: tabular-nums; white-space: nowrap; }
#order { background: none; border: 1px solid var(--border); border-radius: 7px; padding: 3px 10px; font-size: 12.5px; color: var(--ink2); white-space: nowrap; }
#order:hover { background: var(--wash); }
.dot { width: 10px; height: 10px; border-radius: 3px; flex: none; display: inline-block; }

/* ---------- timeline ---------- */
main { padding: 8px 0 40px; }
.month { padding-top: 26px; }
.month h2 { margin: 0 0 6px; font-size: 19px; line-height: 1.25; font-weight: 650; letter-spacing: -0.012em; display: flex; gap: 10px; align-items: baseline; }
.month h2 small { color: var(--muted); font-size: 13px; font-weight: 400; font-variant-numeric: tabular-nums; }
.tl { list-style: none; margin: 0; padding: 0 0 0 26px; position: relative; }
.tl::before { content: ""; position: absolute; left: 6px; top: 6px; bottom: 6px; width: 2px; background: var(--grid); border-radius: 1px; }
.tl > li { position: relative; padding: 12px 0; border-bottom: 1px solid var(--grid); }
.tl > li:last-child { border-bottom: 0; }
.tl > li::before { content: ""; position: absolute; left: -25px; top: 19px; width: 12px; height: 12px; border-radius: 4px;
  background: var(--k, var(--other)); box-shadow: 0 0 0 3px var(--bg); }
.tl > li.k-flyway { --k: var(--s0); }
.tl > li.k-custom { --k: var(--s1); }
.tl > li.k-base { --k: var(--s2); }
.tl > li.gone::before { background: var(--bg); box-shadow: 0 0 0 3px var(--bg), inset 0 0 0 2px var(--k); }
.tl > li.ms { --k: var(--s6); padding: 16px 0; }
.tl > li.ms::before { transform: rotate(45deg); border-radius: 2px; left: -26px; top: 22px; width: 14px; height: 14px; }
.ms .mt { font-weight: 650; font-size: 16px; }
.ms .mx { color: var(--ink2); font-size: 14px; margin: 3px 0 0; max-width: 78ch; }
.head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; }
.ver { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; font-weight: 600; color: var(--k); white-space: nowrap; }
.nm { font-weight: 600; overflow-wrap: anywhere; }
.cls { color: var(--muted); font-size: 12.5px; }
.pill { font-size: 11.5px; border-radius: 999px; padding: 0 8px; line-height: 19px; border: 1px solid var(--border); color: var(--ink2); white-space: nowrap; }
.pill.st-current { border-color: color-mix(in srgb, var(--s5) 55%, transparent); color: var(--s5); }
.pill.st-folded { border-color: color-mix(in srgb, var(--s2) 55%, transparent); color: var(--s2); }
.pill.st-removed, .pill.st-renamed { border-color: color-mix(in srgb, var(--s7) 55%, transparent); color: var(--s7); }
.meta { color: var(--muted); font-size: 12.5px; margin-top: 2px; }
.meta a { color: var(--ink2); }
.notes { margin: 5px 0 0; padding: 0; list-style: none; font-size: 13.5px; color: var(--ink2); }
.notes li::before { content: "\2192\00a0"; color: var(--muted); }
.notes .again { color: var(--s1); font-weight: 600; }
.tables { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 7px; }
.tables code { font-size: 11.5px; background: var(--wash); border-radius: 5px; padding: 1px 6px; color: var(--ink2); }
.tables button { background: none; border: 0; padding: 0 4px; font-size: 12px; color: var(--muted); text-decoration: underline; }
.subj { margin: 7px 0 0; font-size: 13.5px; color: var(--ink2); }
details { margin-top: 3px; font-size: 13px; color: var(--ink2); }
summary { cursor: pointer; color: var(--muted); font-size: 12.5px; }
details pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0 0; padding: 10px 12px; background: var(--raised);
  border: 1px solid var(--grid); border-radius: 8px; font: 12.5px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
#empty { color: var(--muted); padding: 30px 0; }

footer { border-top: 1px solid var(--grid); padding: 22px 0 60px; color: var(--muted); font-size: 13px; }
footer p { margin: 0 0 8px; max-width: 86ch; }

@media (max-width: 640px) {
  .wrap { padding: 0 16px; }
  .top { padding-top: 26px; }
  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 16px; padding: 24px 0; }
  .sep { display: none; }
}
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<div class="wrap">
  <header class="top">
    <div>
      <h1>__TITLE__ migration timeline</h1>
      <p id="lede"></p>
    </div>
    <button id="theme" type="button" title="Switch colour theme">Theme: auto</button>
  </header>
  <div class="stats" id="stats"></div>
</div>
<div class="filterbar"><div class="wrap"><div class="filters">
  <div class="chips" id="kinds" role="group" aria-label="Kind"></div>
  <span class="sep"></span>
  <div class="chips" id="states" role="group" aria-label="Status"></div>
  <input id="q" type="search" placeholder="Search name, table or commit" aria-label="Search migrations">
  <span id="count" role="status"></span>
  <button id="order" type="button">Oldest first</button>
</div></div></div>
<div class="wrap">
<main id="tl"></main>
<p id="empty" hidden>No migration matches these filters.</p>
<footer id="foot"></footer>
</div>
<noscript><p style="padding:24px">This page draws the timeline with JavaScript. Enable it to see the migrations.</p></noscript>
<script type="application/json" id="data">__DATA__</script>
<script>
(() => {
'use strict';
const D = JSON.parse(document.getElementById('data').textContent);
const E = D.entries, C = D.commits;
const MON = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const MONF = ['January','February','March','April','May','June','July','August','September','October','November','December'];
const nf = new Intl.NumberFormat('en-US');
const fmt = n => nf.format(n);
const fmtDay = iso => { const [y, m, d] = iso.split('-').map(Number); return d + ' ' + MON[m - 1] + ' ' + y; };

function h(tag, props, ...kids) {
  const e = document.createElement(tag);
  if (props) for (const k in props) {
    const v = props[k];
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k === 'style') e.style.cssText = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat(Infinity)) if (kid != null) e.append(kid);
  return e;
}

/* commit references link to the web view of the origin remote when it could be read */
const commitRef = i => {
  const c = C[i], short = c.sha.slice(0, 8);
  return D.web ? h('a', { class: 'mono', href: D.web + '/commit/' + c.sha, target: '_blank', rel: 'noopener', text: short })
    : h('span', { class: 'mono', text: short });
};
const at = i => [' in ', commitRef(i), ' (' + fmtDay(C[i].date) + ')'];

const KINDS = [['flyway', 'Flyway', 'var(--s0)'], ['custom', 'Custom', 'var(--s1)'], ['base', 'Base', 'var(--s2)']];
const STATES = [['current', 'Current'], ['folded', 'Folded into base'], ['removed', 'Removed']];
const stateOf = e => e.status === 'renamed' ? 'removed' : e.status;
const STATUS_TEXT = { current: 'current', folded: 'folded into base', removed: 'removed', renamed: 'renamed' };
const flywayName = (key, name) => key + '__' + name;

/* ---------- header figures ---------- */
const count = (kind, st) => E.filter(e => e.kind === kind && (!st || stateOf(e) === st)).length;
const fly = count('flyway'), cus = count('custom');
const cur = E.filter(e => e.kind !== 'base' && e.status === 'current').length;
const fold = E.filter(e => e.status === 'folded').length;
const first = C[0], last = C[C.length - 1];
document.getElementById('lede').textContent =
  `Every Flyway and custom database migration from ${fmtDay(first.date)} to ${fmtDay(last.date)}, read from ${fmt(C.length)} commits of the git history. ` +
  `Each entry shows the commit that added it, the tables it touches and what became of it.`;
const stats = document.getElementById('stats');
[
  [fmt(fly), 'Flyway migrations', `${count('flyway', 'current')} current, ${count('flyway', 'folded')} folded, ${count('flyway', 'removed')} removed`],
  [fmt(cus), 'Custom migrations', `${count('custom', 'current')} current, ${count('custom', 'folded')} folded, ${count('custom', 'removed')} removed or renamed`],
  [fmt(cur), 'Shipped today', 'Flyway and custom migrations still in the code' + (D.base.version ? `, base ${D.base.version}` : '')],
  [fmt(fold), 'Folded into the base', E.some(e => e.kind === 'base') ? 'Replaced by ' + E.filter(e => e.kind === 'base').map(e => flywayName(e.key, e.name) + '.sql').join(', ') : 'No base yet'],
].forEach(([v, l, s]) => stats.append(h('div', { class: 'stat' }, h('div', { class: 'v', text: v }), h('div', { class: 'l', text: l }), h('div', { class: 's', text: s }))));

/* ---------- filters ---------- */
const on = { flyway: true, custom: true, base: true, current: true, folded: true, removed: true };
function chip(parent, key, label, color, n) {
  const b = h('button', { type: 'button', 'aria-pressed': 'true', onclick: () => { on[key] = !on[key]; b.setAttribute('aria-pressed', on[key]); apply(); } },
    color ? h('i', { class: 'dot', style: 'background:' + color }) : null, label, h('span', { class: 'n', text: fmt(n) }));
  parent.append(b);
}
KINDS.forEach(([k, l, c]) => chip(document.getElementById('kinds'), k, l, c, E.filter(e => e.kind === k).length));
STATES.forEach(([k, l]) => chip(document.getElementById('states'), k, l, null, E.filter(e => stateOf(e) === k).length));

/* ---------- entries ---------- */
function notes(e) {
  const out = [];
  if (e.names) out.push(['First added as ' + flywayName(e.key, e.names[0]) + ', the name changed later']);
  if (e.renamedFrom) {
    const [key, name, c] = e.renamedFrom;
    out.push(e.kind === 'custom'
      ? ['Renamed from ' + key, ...at(c), ': ', h('span', { class: 'again', text: 'runs again on servers' }), ', because a custom migration is recorded by its class name']
      : ['Takes over the content of ' + flywayName(key, name), ...at(c)]);
  }
  for (const [c, text] of e.events || []) out.push([text, ...at(c)]);
  if (e.status === 'folded') out.push(['Folded into the base', ...at(e.gone)]);
  else if (e.status === 'renamed') out.push(['Renamed to ' + e.renamedTo[0], ...at(e.gone)]);
  else if (e.status === 'removed' && e.renamedTo) out.push(['Merged into ' + flywayName(e.renamedTo[0], e.renamedTo[1]), ...at(e.gone)]);
  else if (e.status === 'removed') out.push(['Removed', ...at(e.gone)]);
  return out.length ? h('ul', { class: 'notes' }, out.map(n => h('li', null, n))) : null;
}
const TABLES_SHOWN = 14;
function tables(list) {
  if (!list.length) return null;
  const box = h('div', { class: 'tables', 'aria-label': 'Tables' });
  const fill = all => { box.replaceChildren(...(all ? list : list.slice(0, TABLES_SHOWN)).map(t => h('code', { text: t })));
    if (!all && list.length > TABLES_SHOWN) box.append(h('button', { type: 'button', text: `+${list.length - TABLES_SHOWN} more`, onclick: () => fill(true) })); };
  fill(false);
  return box;
}
function commitText(i) {
  const c = C[i];
  return [
    h('p', { class: 'subj', text: c.summary }),
    c.message.length > c.summary.length ? h('details', null, h('summary', { text: 'Full commit message' }), h('pre', { text: c.message })) : null,
  ];
}
function entryNode(e) {
  const sql = e.files.length ? e.files.join(' + ') + ' SQL' : e.kind === 'custom' ? 'Kotlin, custom' : 'Kotlin';
  const title = e.kind === 'custom'
    ? [h('span', { class: 'ver', text: e.version }), h('span', { class: 'nm', text: e.label }), h('code', { class: 'cls', text: e.key })]
    : [h('span', { class: 'ver', text: e.key }), h('span', { class: 'nm', text: e.name })];
  const li = h('li', { class: 'k-' + e.kind + (e.status === 'current' ? '' : ' gone') },
    h('div', { class: 'head' }, title, h('span', { class: 'pill st-' + e.status, text: STATUS_TEXT[e.status] })),
    h('div', { class: 'meta' }, fmtDay(C[e.added].date) + ' · ', commitRef(e.added), ' · ' + sql + (e.tables.length ? ' · ' + e.tables.length + (e.tables.length === 1 ? ' table' : ' tables') : '')),
    notes(e), tables(e.tables), commitText(e.added));
  li._e = e;
  li._hay = [e.key, e.version, e.name, e.label, ...(e.names || []), ...e.tables, C[e.added].message.split('\n')[0]].filter(Boolean).join('\n').toLowerCase();
  return li;
}
function milestoneNode(m) {
  const li = h('li', { class: 'ms' },
    h('div', { class: 'mt', text: m.title }),
    h('div', { class: 'meta' }, fmtDay(C[m.commit].date) + ' · ', commitRef(m.commit)),
    h('p', { class: 'mx', text: m.text }), commitText(m.commit));
  li._hay = (m.title + '\n' + m.text + '\n' + C[m.commit].message.split('\n')[0]).toLowerCase();
  return li;
}

/* items in history order: a commit's milestones come before the migrations it added */
const items = [];
D.milestones.forEach(m => items.push({ c: m.commit, ms: 1, node: milestoneNode(m) }));
E.forEach(e => items.push({ c: e.added, ms: 0, node: entryNode(e) }));
items.forEach((it, i) => it.i = i);
items.sort((a, b) => a.c - b.c || b.ms - a.ms || a.i - b.i);

let newestFirst = true;
const root = document.getElementById('tl');
function build() {
  const ordered = newestFirst ? [...items].reverse() : items;
  const months = [];
  for (const it of ordered) {
    const ym = C[it.c].date.slice(0, 7);
    if (!months.length || months[months.length - 1].ym !== ym) months.push({ ym, items: [] });
    months[months.length - 1].items.push(it);
  }
  root.replaceChildren(...months.map(mo => {
    const [y, m] = mo.ym.split('-').map(Number);
    const n = h('small');
    const sec = h('section', { class: 'month' }, h('h2', null, MONF[m - 1] + ' ' + y, n), h('ol', { class: 'tl' }, mo.items.map(it => it.node)));
    sec._n = n;
    return sec;
  }));
  document.getElementById('order').textContent = newestFirst ? 'Oldest first' : 'Newest first';
  apply();
}
const q = document.getElementById('q');
function apply() {
  const terms = q.value.toLowerCase().split(/\s+/).filter(Boolean);
  let shown = 0;
  for (const it of items) {
    const e = it.node._e;
    const match = terms.every(t => it.node._hay.includes(t));
    const vis = e ? on[e.kind] && on[stateOf(e)] && match : match;
    it.node.hidden = !vis;
    if (vis && e) shown++;
  }
  for (const sec of root.children) {
    const visible = [...sec.querySelectorAll('.tl > li')].filter(li => !li.hidden);
    sec.hidden = !visible.length;
    const n = visible.filter(li => li._e).length;
    sec._n.textContent = n ? (n === 1 ? '1 migration' : n + ' migrations') : '';
  }
  document.getElementById('count').textContent = shown === E.length ? fmt(E.length) + ' migrations' : fmt(shown) + ' of ' + fmt(E.length);
  document.getElementById('empty').hidden = shown > 0 || [...root.children].some(s => !s.hidden);
}
q.addEventListener('input', apply);
document.getElementById('order').addEventListener('click', () => { newestFirst = !newestFirst; build(); });

/* theme: follows the system unless the reader picks one */
const themeBtn = document.getElementById('theme'), MODES = ['auto', 'light', 'dark'];
let mode = 'auto';
function setTheme(mo, save) {
  mode = mo;
  if (mo === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', mo);
  themeBtn.textContent = 'Theme: ' + mo;
  if (save) try { localStorage.setItem('migration-timeline-theme', mo); } catch (e) {}
}
themeBtn.addEventListener('click', () => setTheme(MODES[(MODES.indexOf(mode) + 1) % 3], true));
const params = new URLSearchParams(location.hash.slice(1));
let saved = null; try { saved = localStorage.getItem('migration-timeline-theme'); } catch (e) {}
setTheme(MODES.includes(params.get('theme')) ? params.get('theme') : MODES.includes(saved) ? saved : 'auto');
if (params.get('q')) q.value = params.get('q');

const foot = document.getElementById('foot');
[
  `Flyway migrations are the files named V<version>__<Name> (Kotlin Java migrations, later SQL files with one PostgreSQL and one SQLite variant counted as one migration) and the frozen base B<version>__Base.sql. Renamed and moved files are followed through the history, so a package move is not a new migration. Custom migrations are the classes in the migrations/custom package, with the @Migration version and the logTask label of the commit that added them (the label of a later commit when the first had none, otherwise the class name split into words).`,
  `A custom migration is recorded by its class name once it ran, so a renamed class runs again on every server. Folded means the migration was deleted in the commit that added the base, whose schema already contains its result. Tables come from CREATE, ALTER and DROP TABLE and CREATE INDEX statements and from Kotlin table objects, named by their SQL table name where the object still exists.`,
  (D.base.steppingStone ? `MigrationBase at HEAD: schema base ${D.base.version}, last custom migration of the old line ${D.base.lastCustom}, stepping stone image ${D.base.steppingStone}. ` : '') +
  `Data as of commit ${D.head.sha.slice(0, 8)} on ${fmtDay(D.head.date)}. Regenerate with python3 scripts/migration-timeline.py.`,
].forEach(t => foot.append(h('p', { text: t })));

build();
})();
</script>
</body>
</html>
'''


if __name__ == "__main__":
    sys.exit(main())
