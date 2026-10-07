#!/usr/bin/env python3
"""Commit statistics for this repository and its sibling client checkouts.

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
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SIBLING_REPOS = ("synara-android", "SynaraComposeMultiplatform")
TITLE = "Synara"

USAGE = """Collects commit history and line counts with read-only git commands and writes docs/STATS.html,
one self-contained interactive page (data inlined, no network requests) to open locally in a browser.
Covers this repository and the sibling checkouts next to it (see SIBLING_REPOS); a missing sibling is skipped.
Repository paths given as arguments, or --workspace with a JetBrains jb-workspace.xml, replace that set.
-o writes the page elsewhere, --json also saves the collected data.
Unchanged history gives a byte-identical page. Standard library only."""
TOP_COMMITS_PER_REPO = 8

# --------------------------------------------------------------------------
# File classification
# --------------------------------------------------------------------------

LANGS = {
    "kt": "Kotlin", "kts": "Kotlin script", "java": "Java", "swift": "Swift",
    "rs": "Rust", "slint": "Slint", "ts": "TypeScript", "tsx": "TypeScript",
    "mts": "TypeScript", "js": "JavaScript", "jsx": "JavaScript", "mjs": "JavaScript",
    "cjs": "JavaScript", "svelte": "Svelte", "vue": "Vue", "py": "Python",
    "go": "Go", "c": "C", "h": "C/C++ header", "cpp": "C++", "cc": "C++",
    "hpp": "C/C++ header", "m": "Objective-C", "mm": "Objective-C", "cs": "C#",
    "rb": "Ruby", "php": "PHP", "dart": "Dart", "sh": "Shell", "bash": "Shell",
    "fish": "Shell", "zsh": "Shell", "bat": "Batch", "ps1": "PowerShell",
    "sql": "SQL", "sq": "SQL", "sqm": "SQL", "html": "HTML", "htm": "HTML",
    "css": "CSS", "scss": "CSS", "sass": "CSS", "less": "CSS", "xml": "XML",
    "json": "JSON", "json5": "JSON", "yaml": "YAML", "yml": "YAML", "toml": "TOML",
    "md": "Markdown", "mdx": "Markdown", "txt": "Text", "properties": "Properties",
    "gradle": "Gradle", "nix": "Nix", "proto": "Protobuf", "graphql": "GraphQL",
    "plist": "Property list", "xcstrings": "String catalog", "strings": "Strings",
    "entitlements": "Property list", "pro": "ProGuard", "cfg": "Config",
    "ini": "Config", "conf": "Config", "env": "Config", "tf": "Terraform",
    "lua": "Lua", "luau": "Lua", "qml": "QML", "ex": "Elixir", "exs": "Elixir",
    "patch": "Patch", "diff": "Patch", "dockerfile": "Dockerfile",
}
SPECIAL_NAMES = {
    "dockerfile": "Dockerfile", "makefile": "Makefile", "justfile": "Justfile",
    "cmakelists.txt": "CMake", "rakefile": "Ruby", "gemfile": "Ruby",
}
BINARY_EXT = {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns", "tiff", "avif",
    "heic", "pdf", "ttf", "otf", "woff", "woff2", "eot", "jar", "aar", "apk",
    "aab", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "so", "dylib", "dll",
    "exe", "bin", "dat", "db", "sqlite", "keystore", "jks", "mp3", "flac",
    "ogg", "opus", "wav", "m4a", "mp4", "mov", "webm", "class", "o", "a",
    "wasm", "xcuserstate", "car", "psd",
}
LOCK_NAMES = {
    "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock", "podfile.lock",
    "package.resolved", "gradle.lockfile", "flake.lock", "composer.lock",
    "poetry.lock", "uv.lock", "bun.lock", "bun.lockb", "gemfile.lock",
    "npm-shrinkwrap.json", "pubspec.lock",
}
IDE_DIRS = {".idea", ".vscode", ".fleet", "xcuserdata", ".run"}
IDE_EXT = {"iml", "pbxproj", "xcworkspacedata", "xcscheme", "xcsettings", "xcuserstate"}
GENERATED_DIRS = {
    "node_modules", "vendor", "vendored", "third_party", "third-party", "generated",
    "build", "dist", ".gradle", "pods", "__pycache__", "__generated__",
    "__snapshots__", "schemas",
}
GENERATED_NAMES = {"gradlew", "gradlew.bat", "gradle-wrapper.properties"}
GENERATED_SUFFIX = (
    ".min.js", ".min.css", ".map", ".pb.go", ".g.dart", ".freezed.dart",
    ".generated.ts", ".generated.kt", ".golden", ".snap",
)

R_BINARY = "Binary files (images, fonts, archives)"
R_LOCK = "Lockfiles"
R_IDE = "IDE and project metadata"
R_GEN = "Generated, vendored or snapshot files"
R_SVG = "Vector images (SVG)"
R_SUB = "Submodule links"


def noise_reason(path: str) -> str | None:
    """Reason a path is kept out of the line counts, or None if it counts."""
    low = path.lower()
    parts = low.split("/")
    name = parts[-1]
    ext = name.rsplit(".", 1)[1] if "." in name else ""
    if name in LOCK_NAMES or ext == "lock":
        return R_LOCK
    if ext in IDE_EXT or any(p in IDE_DIRS for p in parts[:-1]):
        return R_IDE
    if (name in GENERATED_NAMES or low.endswith(GENERATED_SUFFIX)
            or any(p in GENERATED_DIRS for p in parts[:-1])):
        return R_GEN
    if ext == "svg":
        return R_SVG
    if ext in BINARY_EXT:
        return R_BINARY
    return None


def language(path: str) -> str:
    name = path.rsplit("/", 1)[-1]
    low = name.lower()
    if low in SPECIAL_NAMES:
        return SPECIAL_NAMES[low]
    if low.startswith("dockerfile"):
        return "Dockerfile"
    if low.startswith(".") and low.count(".") == 1:
        return "Dotfiles"
    if "." not in low:
        return "Other"
    ext = low.rsplit(".", 1)[1]
    return LANGS.get(ext, "." + ext)


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


def is_repo_root(path: Path) -> bool:
    return (path / ".git").exists()


def has_commits(repo: Path) -> bool:
    return bool(git(repo, "rev-parse", "--verify", "--quiet", "HEAD^{commit}", check=False).strip())


def workspace_repos(workspace_file: Path) -> list[tuple[str, Path]]:
    """(display name, path) for each project of a JetBrains workspace file.

    Reads the <project name= path=> entries of the WorkspaceSettings component;
    $PROJECT_DIR$ is the directory that contains the .idea folder.
    """
    project_dir = workspace_file.resolve().parent.parent
    root = ET.parse(workspace_file).getroot()
    found = []
    for comp in root.iter("component"):
        if comp.get("name") != "WorkspaceSettings":
            continue
        for proj in comp.findall("project"):
            raw = proj.get("path")
            if not raw:
                continue
            path = Path(raw.replace("$PROJECT_DIR$", str(project_dir))).resolve()
            found.append((proj.get("name") or path.name, path))
    return found


# --------------------------------------------------------------------------
# Collection
# --------------------------------------------------------------------------

RS, US = "\x1e", "\x1f"
LOG_FORMAT = f"{RS}%H{US}%at{US}%ai{US}%aN{US}%aE{US}%P{US}%s"


def tz_offset_seconds(iso: str) -> int:
    m = re.search(r"([+-])(\d\d)(\d\d)$", iso.strip())
    if not m:
        return 0
    secs = int(m.group(2)) * 3600 + int(m.group(3)) * 60
    return -secs if m.group(1) == "-" else secs


def collect_history(repo: Path, gitlinks: set[str]) -> tuple[list[dict], dict]:
    """All commits reachable from HEAD, with per-commit line stats.

    Merge commits are listed (so the count matches `git rev-list --count HEAD`)
    but git prints no diff for them, so they contribute no lines.
    """
    out = git(repo, "log", "HEAD", "--no-renames", "--numstat", f"--format={LOG_FORMAT}")
    commits = []
    noise = {"add": 0, "del": 0}
    for rec in out.decode("utf-8", "replace").split(RS)[1:]:
        head, _, body = rec.partition("\n")
        sha, at, ai, name, email, parents, subject = (head.split(US) + [""] * 7)[:7]
        add = dele = files = 0
        for line in body.splitlines():
            cols = line.split("\t", 2)
            if len(cols) != 3:
                continue
            a, d, path = cols
            if a == "-" or d == "-":          # binary
                continue
            if path in gitlinks:              # submodule pointer bump, not code
                continue
            if noise_reason(path):
                noise["add"] += int(a)
                noise["del"] += int(d)
                continue
            add += int(a)
            dele += int(d)
            files += 1
        commits.append({
            "sha": sha, "t": int(at) + tz_offset_seconds(ai), "name": name.strip(),
            "email": email.strip().lower(), "merge": len(parents.split()) > 1,
            "subject": subject.strip(), "add": add, "del": dele, "files": files,
        })
    commits.sort(key=lambda c: c["t"])
    return commits, noise


def count_lines(data: bytes) -> int:
    if not data:
        return 0
    n = data.count(b"\n")
    return n if data.endswith(b"\n") else n + 1


def collect_tree(repo: Path) -> dict:
    """Line counts for every blob in HEAD's tree (read from the object store)."""
    raw = git(repo, "ls-tree", "-r", "-z", "HEAD").decode("utf-8", "replace")
    entries, gitlinks = [], set()
    for item in raw.split("\0"):
        if not item:
            continue
        meta, _, path = item.partition("\t")
        mode, typ, sha = meta.split()[:3]
        if typ == "commit":
            gitlinks.add(path)
        elif typ == "blob" and mode != "120000":
            entries.append((sha, path))

    excluded = defaultdict(lambda: [0, 0])     # reason -> [files, lines]
    langs = defaultdict(lambda: [0, 0])        # language -> [lines, files]
    to_read = []
    for sha, path in entries:
        reason = noise_reason(path)
        if reason == R_BINARY:
            excluded[reason][0] += 1
        else:
            to_read.append((sha, path, reason))

    if gitlinks:
        excluded[R_SUB][0] += len(gitlinks)

    blob = git(repo, "cat-file", "--batch", stdin="".join(s + "\n" for s, _, _ in to_read).encode())
    pos = 0
    for sha, path, reason in to_read:
        nl = blob.index(b"\n", pos)
        header = blob[pos:nl].split()
        size = int(header[2]) if len(header) == 3 and header[1] == b"blob" else 0
        data = blob[nl + 1: nl + 1 + size]
        pos = nl + 1 + size + 1
        if b"\0" in data[:8000]:
            excluded[R_BINARY][0] += 1
            continue
        lines = count_lines(data)
        if reason:
            excluded[reason][0] += 1
            excluded[reason][1] += lines
        else:
            lang = langs[language(path)]
            lang[0] += lines
            lang[1] += 1

    return {
        "files": len(entries),
        "gitlinks": gitlinks,
        "langs": sorted(([k, v[0], v[1]] for k, v in langs.items()), key=lambda x: -x[1]),
        "excluded": sorted(([k, v[0], v[1]] for k, v in excluded.items()), key=lambda x: -x[1]),
        "loc": sum(v[0] for v in langs.values()),
        "counted_files": sum(v[1] for v in langs.values()),
    }


# --------------------------------------------------------------------------
# Author identity merging
# --------------------------------------------------------------------------

def identity_keys(name: str, email: str) -> set[str]:
    """Aliases that tie one person's identities together.

    Same lowercase email, same lowercase name, a GitHub noreply address whose
    username equals another identity's name, or a handle in parentheses
    ("Andreas (YungCat)" and "YungCat").
    """
    keys = set()
    low = name.lower().strip()
    if email:
        keys.add("e:" + email)
    if low:
        keys.add("n:" + low)
        for handle in re.findall(r"\(([^)]+)\)", low):
            keys.add("n:" + handle.strip())
    m = re.match(r"^(?:\d+\+)?([^@]+)@users\.noreply\.github\.com$", email)
    if m:
        keys.add("n:" + m.group(1))
    return keys


def merge_authors(all_commits: list[dict]) -> list[dict]:
    parent: dict[str, str] = {}

    def find(x: str) -> str:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    idents = Counter((c["name"], c["email"]) for c in all_commits)
    for (name, email) in idents:
        keys = sorted(identity_keys(name, email))
        for k in keys[1:]:
            parent[find(k)] = find(keys[0])

    groups: dict[str, Counter] = defaultdict(Counter)
    ident_root = {}
    for (name, email), n in idents.items():
        root = find(sorted(identity_keys(name, email))[0])
        ident_root[(name, email)] = root
        groups[root][name] += n

    order = sorted(groups, key=lambda r: -sum(groups[r].values()))
    index = {root: i for i, root in enumerate(order)}
    authors = []
    for root in order:
        names = groups[root]
        display = names.most_common(1)[0][0] or "Unknown"
        authors.append({
            "name": display,
            "bot": display.endswith("[bot]"),
            "aliases": len(names),
        })
    for c in all_commits:
        c["author"] = index[ident_root[(c["name"], c["email"])]]
    return authors


# --------------------------------------------------------------------------
# Assembly
# --------------------------------------------------------------------------

def collect(targets: list[tuple[str, Path]], log=print) -> dict:
    repos, everything, skipped = [], [], []
    seen = set()
    for name, path in targets:
        path = path.resolve()
        if path in seen:
            continue
        seen.add(path)
        if not is_repo_root(path):
            skipped.append([name, "not a git repository root"])
            continue
        if not has_commits(path):
            skipped.append([name, "no commits yet"])
            continue
        t0 = time.perf_counter()
        tree = collect_tree(path)
        commits, noise = collect_history(path, tree["gitlinks"])
        branch = git(path, "rev-parse", "--abbrev-ref", "HEAD").decode().strip()
        head = git(path, "rev-parse", "--short", "HEAD").decode().strip()
        for c in commits:
            c["repo"] = name
        everything.extend(commits)
        repos.append({
            "name": name, "branch": branch, "head": head,
            "files": tree["files"], "countedFiles": tree["counted_files"],
            "loc": tree["loc"], "langs": tree["langs"], "excluded": tree["excluded"],
            "submodules": sorted(tree["gitlinks"]),
            "noiseAdd": noise["add"], "noiseDel": noise["del"],
            "_commits": commits,
        })
        log(f"  {name:<28} {len(commits):>5} commits  {tree['loc']:>8,} lines  "
            f"{tree['files']:>5} files  {time.perf_counter() - t0:.2f}s")

    authors = merge_authors(everything)
    repos.sort(key=lambda r: (-len(r["_commits"]), r["name"].lower()))
    for r in repos:
        commits = r.pop("_commits")
        r["c"] = [[c["t"], c["author"], c["add"], c["del"], c["files"], int(c["merge"])] for c in commits]
        biggest = sorted(range(len(commits)), key=lambda i: -(commits[i]["add"] + commits[i]["del"]))
        r["top"] = [[i, commits[i]["sha"][:7], commits[i]["subject"][:140]]
                    for i in biggest[:TOP_COMMITS_PER_REPO]]
    for a in authors:
        a.pop("aliases", None)
    return {
        "repos": repos, "authors": authors, "skipped": skipped,
    }


def render(data: dict, title: str) -> str:
    payload = json.dumps(data, separators=(",", ":"), ensure_ascii=False)
    payload = payload.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    safe_title = (title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
    return TEMPLATE.replace("__TITLE__", safe_title).replace("__DATA__", payload)


def parse_arguments():
    parser = argparse.ArgumentParser(description=USAGE, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("repos", nargs="*", type=Path, help="git repositories to include (default: this repository and its siblings)")
    parser.add_argument("--workspace", type=Path, help="JetBrains jb-workspace.xml whose projects are the repositories")
    parser.add_argument("-o", "--output", type=Path, default=REPO_ROOT / "docs" / "STATS.html", help="page to write (default: docs/STATS.html in this repository)")
    parser.add_argument("--title", default=TITLE, help=f"page title (default: {TITLE})")
    parser.add_argument("--json", type=Path, dest="json_output", help="also write the collected data to this file")
    return parser.parse_args()


def default_targets() -> list[tuple[str, Path]]:
    targets = [(REPO_ROOT.name, REPO_ROOT)]
    for name in SIBLING_REPOS:
        path = REPO_ROOT.parent / name
        if is_repo_root(path):
            targets.append((name, path))
        else:
            print(f"  {name:<28} skipped (no checkout at {path})")
    return targets


def main() -> int:
    args = parse_arguments()
    started = time.perf_counter()
    if args.repos:
        targets = [(p.resolve().name, p) for p in args.repos]
    elif args.workspace:
        if not args.workspace.is_file():
            print(f"Workspace file not found: {args.workspace}", file=sys.stderr)
            return 1
        try:
            targets = workspace_repos(args.workspace)
        except ET.ParseError as e:
            print(f"Could not parse {args.workspace}: {e}", file=sys.stderr)
            return 1
        if not targets:
            print(f"No <project path=...> entries under WorkspaceSettings in {args.workspace}.", file=sys.stderr)
            return 1
    else:
        targets = default_targets()
    print(f"Collecting {len(targets)} repositories")
    data = collect(targets)
    for name, why in data["skipped"]:
        print(f"  {name:<28} skipped ({why})")
    if not data["repos"]:
        print("None of the repositories has any commits.", file=sys.stderr)
        return 1

    data["title"] = args.title
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(data, args.title), encoding="utf-8", newline="\n")
    print(f"Wrote {args.output} ({args.output.stat().st_size / 1024:,.0f} KB)")
    if args.json_output:
        args.json_output.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    print(f"Done in {time.perf_counter() - started:.2f}s")
    return 0



TEMPLATE = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__ commit history</title>
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
.wrap { max-width: 1180px; margin: 0 auto; padding: 0 28px; }
button { font: inherit; color: inherit; cursor: pointer; }
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

/* ---------- repo switcher (doubles as the colour legend) ---------- */
.switchbar { position: sticky; top: 0; z-index: 20; background: color-mix(in srgb, var(--bg) 88%, transparent);
  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); border-bottom: 1px solid var(--grid); }
#switch { display: flex; gap: 6px; padding: 10px 0; overflow-x: auto; scrollbar-width: none; }
#switch::-webkit-scrollbar { display: none; }
#switch button {
  flex: none; display: inline-flex; align-items: center; gap: 8px; background: none;
  border: 1px solid transparent; border-radius: 999px; padding: 6px 13px 6px 11px; font-size: 14px; color: var(--ink2);
}
#switch button:hover { background: var(--wash); }
#switch button[aria-pressed="true"] { background: var(--ink); color: var(--bg); font-weight: 600; }
#switch button .n { font-variant-numeric: tabular-nums; opacity: .7; font-weight: 400; font-size: 13px; }
.dot { width: 10px; height: 10px; border-radius: 3px; flex: none; display: inline-block; }
#switch button[aria-pressed="true"] .dot { box-shadow: 0 0 0 1.5px var(--bg); }

/* ---------- panels ---------- */
main { padding-bottom: 40px; }
.panel { padding: 30px 0 6px; min-width: 0; }
.panel + .panel, .two + .panel, .panel + .two, .stats + .panel { border-top: 1px solid var(--grid); }
.hero { padding-top: 26px; }
.ph { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
h2 { margin: 0; font-size: 19px; line-height: 1.25; font-weight: 650; letter-spacing: -0.012em; }
.sub { margin: 3px 0 0; color: var(--muted); font-size: 13.5px; max-width: 70ch; }
.tbl-btn { flex: none; background: none; border: 1px solid var(--border); border-radius: 7px; padding: 3px 10px;
  font-size: 12.5px; color: var(--ink2); white-space: nowrap; }
.tbl-btn:hover { background: var(--wash); }
.legend { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 0 0 8px; font-size: 13px; color: var(--ink2); min-height: 0; }
.legend:empty { display: none; }
.legend span { display: inline-flex; align-items: center; gap: 7px; }
.chart { position: relative; min-width: 0; }
.chart svg { display: block; overflow: visible; }
.chart svg:focus-visible { outline-offset: 4px; }
.two { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 0 48px; border-top: 1px solid var(--grid); }
.two > .panel { border-top: 0; }
svg text { font-size: 11.5px; fill: var(--muted); font-variant-numeric: tabular-nums; }
svg .lbl { fill: var(--ink2); font-weight: 600; }
.gridline { stroke: var(--grid); stroke-width: 1; }
.baseline { stroke: var(--axis); stroke-width: 1; }
.seg { transition: opacity .12s; }

/* ---------- figures ---------- */
.stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 26px 28px; padding: 30px 0 32px; border-top: 1px solid var(--grid); }
.stat .v { font-size: clamp(22px, 2.9vw, 31px); font-weight: 650; letter-spacing: -0.02em; line-height: 1.1; white-space: nowrap; }
.stat .l { color: var(--ink2); font-size: 13.5px; margin-top: 5px; }
.stat .s { color: var(--muted); font-size: 12.5px; }

/* ---------- language bars ---------- */
.bars { display: grid; grid-template-columns: max-content minmax(40px, 1fr) max-content; gap: 9px 12px; align-items: center; }
.bars .row { display: contents; }
.bars .name { font-size: 13.5px; color: var(--ink2); white-space: nowrap; }
.bars .track { display: flex; gap: 2px; height: 14px; min-width: 0; border-radius: 3px; outline-offset: 3px; }
.bars .track i { display: block; height: 100%; min-width: 1px; }
.bars .track i:last-child { border-radius: 0 4px 4px 0; }
.bars .val { font-size: 13.5px; font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
.bars .val small { color: var(--muted); font-size: 12px; margin-left: 6px; display: inline-block; min-width: 2.6em; }
details { margin-top: 16px; font-size: 13px; color: var(--ink2); }
summary { cursor: pointer; color: var(--muted); }
details ul { margin: 8px 0 0; padding-left: 18px; }

/* ---------- tables ---------- */
.scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.tablewrap { max-height: 340px; overflow: auto; border: 1px solid var(--grid); border-radius: 8px; }
table { border-collapse: collapse; width: 100%; font-size: 13.5px; }
th, td { text-align: right; padding: 8px 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }
th:first-child, td:first-child { text-align: left; padding-left: 0; }
th:last-child, td:last-child { padding-right: 0; }
.tablewrap th:first-child, .tablewrap td:first-child { padding-left: 12px; }
.tablewrap th:last-child, .tablewrap td:last-child { padding-right: 12px; }
th { color: var(--muted); font-weight: 500; font-size: 12.5px; border-bottom: 1px solid var(--axis); position: sticky; top: 0; background: var(--bg); }
td { border-bottom: 1px solid var(--grid); }
tr:last-child td { border-bottom: 0; }
td.l, th.l { text-align: left; }
.who { display: inline-flex; align-items: center; gap: 8px; }
.tag { font-size: 11px; color: var(--muted); border: 1px solid var(--border); border-radius: 999px; padding: 0 7px; line-height: 17px; }
.minibox { display: inline-block; text-align: left; margin-right: 10px; vertical-align: middle; line-height: 0; }
.mini { display: inline-flex; gap: 2px; height: 10px; }
.mini i { display: block; height: 100%; min-width: 1px; }
.mini i:last-child { border-radius: 0 3px 3px 0; }
.repo-btn { background: none; border: 0; padding: 0; display: inline-flex; align-items: center; gap: 8px; font-weight: 600; }
.repo-btn:hover { text-decoration: underline; }
tr.sel td { background: var(--wash); }
tr.sel td:first-child { box-shadow: -10px 0 0 var(--wash); }
tr.sel td:last-child { box-shadow: 10px 0 0 var(--wash); }
td small { color: var(--muted); font-weight: 400; margin-left: 6px; }

/* ---------- records & largest commits ---------- */
.facts { margin: 0; display: grid; grid-template-columns: max-content 1fr; gap: 0; font-size: 14px; }
.facts dt, .facts dd { margin: 0; padding: 9px 0; border-bottom: 1px solid var(--grid); }
.facts dt { color: var(--ink2); padding-right: 20px; }
.facts dd { text-align: right; font-weight: 600; }
.facts dd small { display: block; color: var(--muted); font-weight: 400; font-size: 12.5px; }
.facts > :nth-last-child(-n+2) { border-bottom: 0; }
.big { list-style: none; margin: 0; padding: 0; }
.big li { display: grid; grid-template-columns: minmax(0, 1fr) max-content; gap: 2px 20px; padding: 10px 0; border-bottom: 1px solid var(--grid); }
.big li:last-child { border-bottom: 0; }
.big .subj { font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.big .meta { color: var(--muted); font-size: 12.5px; }
.big .meta .dot { margin-right: 7px; vertical-align: -1px; }
.big .delta { grid-row: span 2; align-self: center; text-align: right; font-variant-numeric: tabular-nums; font-size: 13.5px; }
.big .delta b { display: block; font-weight: 600; }
.big .delta span { display: block; color: var(--muted); }
.heatkey { display: flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 12px; color: var(--muted); flex-wrap: wrap; }
.heatkey i { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.heatkey span { margin-right: 8px; }

/* ---------- tooltip ---------- */
#tip { position: fixed; z-index: 50; pointer-events: none; background: var(--raised); color: var(--ink);
  border: 1px solid var(--border); border-radius: 9px; box-shadow: var(--shadow); padding: 9px 12px; font-size: 13px;
  max-width: min(320px, calc(100vw - 16px)); left: 0; top: 0; visibility: hidden; }
#tip .th { color: var(--ink2); font-size: 12.5px; margin-bottom: 5px; }
#tip .tr { display: grid; grid-template-columns: 14px minmax(0, 1fr) auto; gap: 8px; align-items: center; line-height: 1.55; }
#tip .tr .k { width: 12px; height: 3px; border-radius: 2px; }
#tip .tr .nm { color: var(--ink2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#tip .tr b { font-weight: 650; font-variant-numeric: tabular-nums; white-space: nowrap; }
#tip .tt { border-top: 1px solid var(--grid); margin-top: 4px; padding-top: 4px; }

footer { border-top: 1px solid var(--grid); padding: 22px 0 60px; color: var(--muted); font-size: 13px; }
footer p { margin: 0 0 8px; max-width: 86ch; }
footer code { font-size: 12px; color: var(--ink2); }

@media (max-width: 860px) {
  .two { grid-template-columns: minmax(0, 1fr); }
  .two > .panel + .panel { border-top: 1px solid var(--grid); }
}
@media (max-width: 640px) {
  .wrap { padding: 0 16px; }
  .top { padding-top: 26px; }
  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 16px; padding: 24px 0; }
  .bars .val small { display: none; }
  .tbl-btn { display: none; }
  .facts { font-size: 13.5px; }
}
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<div class="wrap">
  <header class="top">
    <div>
      <h1>__TITLE__ commit history</h1>
      <p id="lede"></p>
    </div>
    <button id="theme" type="button" title="Switch colour theme">Theme: auto</button>
  </header>
</div>
<div class="switchbar"><div class="wrap"><nav id="switch" aria-label="Repository"></nav></div></div>
<div class="wrap">
<main>
  <section class="panel hero" data-key="wave">
    <div class="ph"><div><h2>Lines written and removed</h2><p class="sub"></p></div><button class="tbl-btn" type="button">Show table</button></div>
    <div class="legend"></div><div class="chart"></div><div class="tablewrap" hidden></div>
  </section>

  <div class="stats" id="stats"></div>

  <section class="panel" data-key="commits">
    <div class="ph"><div><h2>Commits</h2><p class="sub"></p></div><button class="tbl-btn" type="button">Show table</button></div>
    <div class="legend"></div><div class="chart"></div><div class="tablewrap" hidden></div>
  </section>

  <section class="panel" data-key="growth">
    <div class="ph"><div><h2>Codebase size over time</h2><p class="sub"></p></div><button class="tbl-btn" type="button">Show table</button></div>
    <div class="legend"></div><div class="chart"></div><div class="tablewrap" hidden></div>
  </section>

  <div class="two">
    <section class="panel" data-key="langs">
      <div class="ph"><div><h2>Lines of code by language</h2><p class="sub"></p></div></div>
      <div class="legend"></div><div class="chart"></div>
    </section>
    <section class="panel" data-key="punch">
      <div class="ph"><div><h2>When commits happen</h2><p class="sub">Weekday and hour of day, in each author's own time zone.</p></div><button class="tbl-btn" type="button">Show table</button></div>
      <div class="chart"></div><div class="tablewrap" hidden></div>
    </section>
  </div>

  <section class="panel" data-key="cal">
    <div class="ph"><div><h2>Daily activity</h2><p class="sub"></p></div><button class="tbl-btn" type="button">Show table</button></div>
    <div class="chart"></div><div class="tablewrap" hidden></div>
  </section>

  <section class="panel" data-key="people">
    <div class="ph"><div><h2>Contributors</h2><p class="sub"></p></div></div>
    <div class="chart scroll"></div>
  </section>

  <div class="two">
    <section class="panel" data-key="records">
      <div class="ph"><div><h2>Records</h2></div></div>
      <div class="chart"></div>
    </section>
    <section class="panel" data-key="big">
      <div class="ph"><div><h2>Largest commits</h2><p class="sub">By lines added plus deleted, merges and uncounted files aside.</p></div></div>
      <div class="chart"></div>
    </section>
  </div>

  <section class="panel" data-key="repos">
    <div class="ph"><div><h2>Repositories compared</h2><p class="sub">Select a repository to focus the whole page on it.</p></div></div>
    <div class="chart scroll"></div>
  </section>
</main>
<footer id="foot"></footer>
</div>
<div id="tip" role="status"></div>
<noscript><p style="padding:24px">This page draws its charts with JavaScript. Enable it to see the statistics.</p></noscript>
<script type="application/json" id="data">__DATA__</script>
<script>
(() => {
'use strict';
const D = JSON.parse(document.getElementById('data').textContent);
const R = D.repos, A = D.authors;
const MON = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const MONF = ['January','February','March','April','May','June','July','August','September','October','November','December'];
const WD = ['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];
const nf = new Intl.NumberFormat('en-US');
const fmt = n => nf.format(Math.round(n));
const plural = (n, w) => fmt(n) + ' ' + w + (n === 1 ? '' : 's');
const pct = x => (x >= 0.995 ? '100' : x < 0.01 && x > 0 ? '<1' : Math.round(x * 100)) + '%';
const col = i => i < 0 ? 'var(--all)' : i < 8 ? 'var(--s' + i + ')' : 'var(--other)';

/* dates: t is "author-local seconds" (epoch + the commit's own UTC offset), so UTC getters give wall-clock values */
const day = t => Math.floor(t / 86400);
const dObj = dayIdx => new Date(dayIdx * 86400000);
const wdOf = dayIdx => ((dayIdx + 3) % 7 + 7) % 7;                 // Monday = 0
const fmtDay = (dayIdx, year = true) => { const d = dObj(dayIdx); return d.getUTCDate() + ' ' + MON[d.getUTCMonth()] + (year ? ' ' + d.getUTCFullYear() : ''); };

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
function s(tag, attrs, parent, text) {
  const e = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const k in attrs) if (attrs[k] != null) e.setAttribute(k, attrs[k]);
  if (text != null) e.textContent = text;
  if (parent) parent.append(e);
  return e;
}
const dot = i => h('i', { class: 'dot', style: 'background:' + col(i) });

/* ---------- state ---------- */
let sel = -1;
const showTable = {};
const panels = {};
document.querySelectorAll('.panel').forEach(p => {
  const key = p.dataset.key;
  panels[key] = { root: p, sub: p.querySelector('.sub'), legend: p.querySelector('.legend'),
                  chart: p.querySelector('.chart'), table: p.querySelector('.tablewrap'), btn: p.querySelector('.tbl-btn') };
  if (panels[key].btn) panels[key].btn.addEventListener('click', () => { showTable[key] = !showTable[key]; applyTableState(key); });
});
function applyTableState(key) {
  const p = panels[key]; if (!p.btn) return;
  const on = !!showTable[key];
  p.table.hidden = !on; p.chart.hidden = on; if (p.legend) p.legend.hidden = on;
  p.btn.textContent = on ? 'Show chart' : 'Show table';
  p.btn.setAttribute('aria-pressed', on);
  if (!on) render();
}
function setTable(key, head, rows) {
  const t = h('table', null,
    h('thead', null, h('tr', null, head.map(x => h('th', { text: x, scope: 'col' })))),
    h('tbody', null, rows.map(r => h('tr', null, r.map(x => h('td', { text: x }))))));
  panels[key].table.replaceChildren(t);
}
function setLegend(key, ids) {
  const el = panels[key].legend; if (!el) return;
  el.replaceChildren(...(ids.length > 1 ? ids.map(i => h('span', null, dot(i), R[i].name)) : []));
}

/* ---------- tooltip ---------- */
const tipEl = document.getElementById('tip');
const tip = {
  show(x, y, head, rows, total) {
    const kids = [];
    if (head) kids.push(h('div', { class: 'th', text: head }));
    for (const r of rows) kids.push(h('div', { class: 'tr' },
      r.c != null ? h('span', { class: 'k', style: 'background:' + r.c }) : h('span'),
      h('span', { class: 'nm', text: r.n }), h('b', { text: r.v })));
    if (total) kids.push(h('div', { class: 'tr tt' }, h('span'), h('span', { class: 'nm', text: total.n }), h('b', { text: total.v })));
    tipEl.replaceChildren(...kids);
    const w = tipEl.offsetWidth, hh = tipEl.offsetHeight, vw = document.documentElement.clientWidth, vh = window.innerHeight;
    let L = x + 16; if (L + w > vw - 8) L = x - 16 - w; if (L < 8) L = Math.max(8, Math.min(vw - w - 8, x - w / 2));
    let T = y - hh - 14; if (T < 8) T = Math.min(vh - hh - 8, y + 18);
    tipEl.style.transform = `translate(${Math.round(L)}px,${Math.round(T)}px)`;
    tipEl.style.visibility = 'visible';
  },
  hide() { tipEl.style.visibility = 'hidden'; }
};
window.addEventListener('scroll', tip.hide, { passive: true });

/* ---------- aggregation ---------- */
function compute(sel) {
  const ids = sel < 0 ? R.map((_, i) => i) : [sel];
  const cs = [];
  for (const r of ids) { const c = R[r].c; for (let j = 0; j < c.length; j++) { const x = c[j]; cs.push({ r, j, t: x[0], a: x[1], add: x[2], del: x[3], f: x[4], m: x[5] }); } }
  cs.sort((p, q) => p.t - q.t);
  const d0 = day(cs[0].t), d1 = day(cs[cs.length - 1].t), span = d1 - d0 + 1;
  const gran = span > 800 ? 'month' : 'week';
  const bOf = gran === 'week' ? t => Math.floor((day(t) + 3) / 7)
                              : t => { const d = new Date(t * 1000); return d.getUTCFullYear() * 12 + d.getUTCMonth(); };
  const b0 = bOf(cs[0].t), n = bOf(cs[cs.length - 1].t) - b0 + 1;
  const bStart = i => gran === 'week' ? (b0 + i) * 7 - 3 : Date.UTC(Math.floor((b0 + i) / 12), (b0 + i) % 12, 1) / 86400000;
  const bLabel = i => { if (gran === 'week') return 'Week of ' + fmtDay(bStart(i)); const d = dObj(bStart(i)); return MONF[d.getUTCMonth()] + ' ' + d.getUTCFullYear(); };
  const zero = () => new Array(n).fill(0);
  const per = {}; ids.forEach(r => per[r] = { n: zero(), add: zero(), del: zero() });
  const days = new Map(), people = new Map();
  const punch = Array.from({ length: 7 }, () => new Array(24).fill(0));
  let add = 0, del = 0, merges = 0;
  for (const c of cs) {
    const b = bOf(c.t) - b0, p = per[c.r];
    p.n[b]++; p.add[b] += c.add; p.del[b] += c.del;
    add += c.add; del += c.del; merges += c.m;
    const di = day(c.t);
    let dd = days.get(di); if (!dd) days.set(di, dd = { n: 0, per: {} });
    dd.n++; dd.per[c.r] = (dd.per[c.r] || 0) + 1;
    punch[wdOf(di)][Math.floor((c.t % 86400 + 86400) % 86400 / 3600)]++;
    let pp = people.get(c.a); if (!pp) people.set(c.a, pp = { a: c.a, n: 0, add: 0, del: 0, days: new Set(), first: c.t, last: c.t, per: {} });
    pp.n++; pp.add += c.add; pp.del += c.del; pp.days.add(di); pp.last = c.t; pp.per[c.r] = (pp.per[c.r] || 0) + 1;
  }
  const sorted = [...days.keys()].sort((a, b) => a - b);
  let best = { len: 0, end: 0 }, run = 0, busiest = { d: sorted[0], n: 0 };
  sorted.forEach((d, i) => {
    run = i && sorted[i - 1] === d - 1 ? run + 1 : 1;
    if (run > best.len) best = { len: run, end: d };
    if (days.get(d).n > busiest.n) busiest = { d, n: days.get(d).n };
  });
  const langs = new Map();
  for (const r of ids) for (const [name, lines, files] of R[r].langs) {
    let L = langs.get(name); if (!L) langs.set(name, L = { name, lines: 0, files: 0, per: {} });
    L.lines += lines; L.files += files; L.per[r] = lines;
  }
  const excluded = new Map();
  for (const r of ids) for (const [why, files, lines] of R[r].excluded) {
    const e = excluded.get(why) || [0, 0]; e[0] += files; e[1] += lines; excluded.set(why, e);
  }
  return { ids, cs, d0, d1, span, gran, n, bStart, bLabel, per, days, sorted, people: [...people.values()].sort((a, b) => b.n - a.n),
    punch, add, del, merges, best, busiest, langs: [...langs.values()].sort((a, b) => b.lines - a.lines), excluded,
    loc: ids.reduce((x, r) => x + R[r].loc, 0), files: ids.reduce((x, r) => x + R[r].files, 0),
    counted: ids.reduce((x, r) => x + R[r].countedFiles, 0) };
}

/* ---------- chart plumbing ---------- */
function niceStep(raw) {
  const p = Math.pow(10, Math.floor(Math.log10(raw || 1))), f = raw / p;
  return Math.max(1, (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p);
}
function axisFmt(maxAbs) {
  if (maxAbs >= 1e6) return v => v === 0 ? '0' : +(v / 1e6).toFixed(2) + 'M';
  if (maxAbs >= 1e4) return v => v === 0 ? '0' : +(v / 1e3).toFixed(1) + 'K';
  return v => fmt(v);
}
function frame(host, H, label) {
  host.replaceChildren();
  const W = Math.max(240, host.clientWidth);
  const svg = s('svg', { width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': label, tabindex: 0 });
  host.append(svg);
  const m = { l: 44, r: 6, t: 16, b: 26 };
  return { svg, W, H, m, iw: W - m.l - m.r, ih: H - m.t - m.b };
}
function xAxis(F, S) {
  const { svg, m, iw, H, W } = F, slot = iw / S.n;
  let lastEnd = -1e9, prevM = -1;
  for (let i = 0; i < S.n; i++) {
    const d = dObj(S.bStart(i)), mo = d.getUTCMonth(), yr = d.getUTCFullYear();
    const isNew = S.gran === 'week' ? (i === 0 || mo !== prevM) : true;
    prevM = mo;
    if (!isNew) continue;
    const withYear = i === 0 || mo === 0 || (S.gran === 'week' && lastEnd < 0);
    const txt = MON[mo] + (withYear ? ' ' + yr : '');
    const x = m.l + i * slot + (S.gran === 'week' ? 0 : slot / 2), w = txt.length * 6.6;
    const start = S.gran === 'week' ? x : x - w / 2;
    if (start < lastEnd + 10 || start + w > W) { if (mo === 0 && start + w <= W && start >= lastEnd - 4) {} else continue; }
    s('text', { x, y: H - 7, 'text-anchor': S.gran === 'week' ? 'start' : 'middle' }, svg, txt);
    lastEnd = start + w;
  }
}
function yAxis(F, ticks, y, f, signed) {
  for (const v of ticks) {
    const yy = Math.round(y(v)) + .5;
    s('line', { x1: F.m.l, x2: F.W - F.m.r, y1: yy, y2: yy, class: v === 0 ? 'baseline' : 'gridline' }, F.svg);
    s('text', { x: F.m.l - 8, y: yy + 4, 'text-anchor': 'end' }, F.svg, (signed && v > 0 ? '+' : signed && v < 0 ? '−' : '') + f(Math.abs(v)));
  }
}
function topRounded(x, y, w, hh, r, down) {
  r = Math.max(0, Math.min(r, w / 2, hh));
  return down
    ? `M${x},${y}H${x + w}V${y + hh - r}Q${x + w},${y + hh} ${x + w - r},${y + hh}H${x + r}Q${x},${y + hh} ${x},${y + hh - r}Z`
    : `M${x},${y + hh}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + hh}Z`;
}
/* shared hover + keyboard layer for time charts: pointer finds the bucket, arrows step through it */
function hoverLayer(F, n, onShow, onHide) {
  const { svg, m, iw, ih } = F, slot = iw / n;
  const hit = s('rect', { x: m.l, y: m.t, width: iw, height: ih, fill: 'transparent' }, svg);
  let cur = n - 1;
  const at = (i, cx, cy) => { cur = i; onShow(i, cx, cy); };
  hit.addEventListener('pointermove', e => {
    const b = svg.getBoundingClientRect();
    at(Math.max(0, Math.min(n - 1, Math.floor((e.clientX - b.left - m.l) / slot))), e.clientX, e.clientY);
  });
  hit.addEventListener('pointerleave', () => { onHide(); tip.hide(); });
  const kb = i => { const b = svg.getBoundingClientRect(); at(i, b.left + m.l + (i + .5) * slot, b.top + m.t + 30); };
  svg.addEventListener('keydown', e => {
    const k = e.key; let i = cur;
    if (k === 'ArrowLeft') i--; else if (k === 'ArrowRight') i++; else if (k === 'Home') i = 0; else if (k === 'End') i = n - 1;
    else if (k === 'Escape') { onHide(); tip.hide(); return; } else return;
    e.preventDefault(); kb(Math.max(0, Math.min(n - 1, i)));
  });
  svg.addEventListener('focus', () => { if (svg.matches(':focus-visible')) kb(cur); });
  svg.addEventListener('blur', () => { onHide(); tip.hide(); });
}

/* ---------- stacked columns; mirror = additions up, deletions down ---------- */
function barChart(key, S, o) {
  const P = panels[key], ids = S.ids, n = S.n;
  const up = ids.map(r => o.up(S.per[r])), down = o.down ? ids.map(r => o.down(S.per[r])) : null;
  const sum = arrs => arrs[0].map((_, i) => arrs.reduce((x, a) => x + a[i], 0));
  const upT = sum(up), downT = down ? sum(down) : null;
  /* a single runaway bucket would flatten everything else: clip it, mark the break, print its value */
  const capOf = tot => { const v = [...tot].sort((a, b) => b - a); return v.length > 8 && v[2] > 0 && v[0] > 3 * v[2] ? v[2] * 1.25 : Infinity; };
  const capU = capOf(down ? upT.concat(downT) : upT), capD = capU;
  const maxU = Math.min(Math.max(...upT), capU), maxD = down ? Math.min(Math.max(...downT), capD) : 0;
  const narrow = P.chart.clientWidth < 520;
  const F = frame(P.chart, o.height ? (narrow ? o.height * .78 : o.height) : (narrow ? 190 : 230), o.label);
  const { svg, m, iw, ih } = F;
  const step = niceStep((maxU + maxD) / (down ? 6 : 4)) || 1;
  const topV = Math.max(step, Math.ceil(maxU / step) * step), botV = down ? Math.ceil(maxD / step) * step : 0;
  const k = ih / (topV + botV), y = v => m.t + (topV - v) * k;
  const ticks = []; for (let v = -botV; v <= topV + step / 2; v += step) ticks.push(Math.abs(v) < step / 2 ? 0 : v);
  yAxis(F, ticks, y, axisFmt(Math.max(topV, botV)), !!down);
  const slot = iw / n, bw = Math.max(1, Math.min(24, slot >= 5 ? slot - 2 : slot * .72));
  const cols = [];
  const stack = (i, arrs, tot, cap, dir) => {
    const scale = tot[i] > cap ? cap / tot[i] : 1;
    let off = 0; const x = m.l + i * slot + (slot - bw) / 2;
    const live = arrs.map((a, si) => [si, a[i]]).filter(p => p[1] > 0);
    live.forEach(([si, v], idx) => {
      let hh = v * scale * k; const last = idx === live.length - 1;
      const y0 = dir > 0 ? y(0) - off - hh : y(0) + off;
      off += hh;
      const gap = last ? 0 : hh >= 7 ? 2 : hh > 3 ? 1 : 0; hh -= gap;
      if (hh < .4) hh = Math.min(.8, hh + gap);
      const yy = dir > 0 ? y0 + gap : y0;
      const el = last ? s('path', { d: topRounded(x, yy, bw, hh, 4, dir < 0) }, cols[i])
                      : s('rect', { x, y: yy, width: bw, height: hh }, cols[i]);
      el.setAttribute('style', 'fill:' + col(ids[si]));
    });
    if (scale < 1) {
      const tipY = dir > 0 ? y(0) - off : y(0) + off, by = tipY + dir * 9;
      s('path', { d: `M${x - 2},${by + 3}L${x + bw + 2},${by - 3}`, style: 'stroke:var(--bg);stroke-width:3;fill:none' }, cols[i]);
      const tx = Math.max(m.l + 22, Math.min(F.W - 24, x + bw / 2));
      s('text', { x: tx, y: dir > 0 ? tipY - 4 : tipY + 12, 'text-anchor': 'middle', class: 'lbl' }, svg, (down ? (dir > 0 ? '+' : '−') : '') + fmt(tot[i]));
    }
  };
  const band = s('rect', { y: m.t, width: Math.max(slot, bw + 4), height: ih, style: 'fill:var(--wash)', visibility: 'hidden' }, svg);
  for (let i = 0; i < n; i++) {
    cols.push(s('g', { class: 'seg' }, svg));
    stack(i, up, upT, capU, 1);
    if (down) stack(i, down, downT, capD, -1);
  }
  xAxis(F, S);
  hoverLayer(F, n, (i, cx, cy) => {
    band.setAttribute('x', m.l + i * slot - (Math.max(slot, bw + 4) - slot) / 2); band.setAttribute('visibility', 'visible');
    const rows = [];
    ids.forEach((r, si) => { if (up[si][i] || (down && down[si][i])) rows.push({ c: col(r), n: R[r].name, v: o.val(up[si][i], down && down[si][i]) }); });
    if (!rows.length) rows.push({ n: o.empty, v: '' });
    tip.show(cx, cy, S.bLabel(i), rows, rows.length > 1 ? { n: 'All', v: o.val(upT[i], down && downT[i]) } : null);
  }, () => band.setAttribute('visibility', 'hidden'));
  setLegend(key, ids);
  const head = [S.gran === 'week' ? 'Week starting' : 'Month'], rows = [];
  ids.forEach(r => { if (down) head.push(R[r].name + ' added', R[r].name + ' deleted'); else head.push(R[r].name); });
  for (let i = n - 1; i >= 0; i--) {
    const row = [S.gran === 'week' ? fmtDay(S.bStart(i)) : S.bLabel(i)];
    ids.forEach((r, si) => { row.push(fmt(up[si][i])); if (down) row.push(fmt(down[si][i])); });
    rows.push(row);
  }
  setTable(key, head, rows);
}

/* ---------- cumulative net lines, stacked by repo ---------- */
function growthChart(key, S) {
  const P = panels[key], ids = S.ids, n = S.n;
  const cum = ids.map(r => { let x = 0; return S.per[r].add.map((a, i) => (x += a - S.per[r].del[i], Math.max(0, x))); });
  const tot = cum[0].map((_, i) => cum.reduce((x, a) => x + a[i], 0));
  const narrow = P.chart.clientWidth < 520;
  const F = frame(P.chart, narrow ? 200 : 250, 'Cumulative net lines over time');
  const { svg, m, iw, ih } = F;
  const max = Math.max(...tot, 1), step = niceStep(max / 4), topV = Math.ceil(max / step) * step;
  const y = v => m.t + (topV - v) / topV * ih, slot = iw / n, x = i => m.l + (i + .5) * slot;
  const ticks = []; for (let v = 0; v <= topV + step / 2; v += step) ticks.push(v);
  yAxis(F, ticks, y, axisFmt(topV));
  const multi = ids.length > 1, base = new Array(n).fill(0), tops = [];
  ids.forEach((r, si) => {
    const top = base.map((b, i) => b + cum[si][i]);
    const line = top.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
    const area = line + base.map((_, j) => { const i = n - 1 - j; return `L${x(i).toFixed(1)},${y(base[i]).toFixed(1)}`; }).join('') + 'Z';
    if (n > 1) {
      s('path', { d: area, style: `fill:${col(r)};fill-opacity:${multi ? .86 : .14}` }, svg);
      s('path', { d: line, style: multi ? 'stroke:var(--bg);stroke-width:2;fill:none' : `stroke:${col(r)};stroke-width:2;fill:none`, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }, svg);
    }
    tops.push(top); for (let i = 0; i < n; i++) base[i] = top[i];
  });
  if (n === 1) s('circle', { cx: x(0), cy: y(tot[0]), r: 5, style: `fill:${col(ids[0])};stroke:var(--bg);stroke-width:2` }, svg);
  s('text', { x: F.W - m.r, y: Math.max(11, y(tot[n - 1]) - 8), 'text-anchor': 'end', class: 'lbl' }, svg, fmt(tot[n - 1]) + ' lines');
  xAxis(F, S);
  const cross = s('line', { y1: m.t, y2: m.t + ih, class: 'baseline', visibility: 'hidden' }, svg);
  const dots = ids.map(r => s('circle', { r: 4.5, style: `fill:${col(r)};stroke:var(--bg);stroke-width:2`, visibility: 'hidden' }, svg));
  hoverLayer(F, n, (i, cx, cy) => {
    cross.setAttribute('x1', x(i)); cross.setAttribute('x2', x(i)); cross.setAttribute('visibility', 'visible');
    dots.forEach((d, si) => { d.setAttribute('cx', x(i)); d.setAttribute('cy', y(tops[si][i])); d.setAttribute('visibility', 'visible'); });
    const rows = ids.map((r, si) => ({ c: col(r), n: R[r].name, v: fmt(cum[si][i]) })).reverse();
    tip.show(cx, cy, 'By end of ' + S.bLabel(i).replace(/^Week/, 'week'), rows, multi ? { n: 'All', v: fmt(tot[i]) } : null);
  }, () => { cross.setAttribute('visibility', 'hidden'); dots.forEach(d => d.setAttribute('visibility', 'hidden')); });
  setLegend(key, ids);
  const rows = [];
  for (let i = n - 1; i >= 0; i--) rows.push([S.gran === 'week' ? fmtDay(S.bStart(i)) : S.bLabel(i), ...cum.map(c => fmt(c[i])), ...(multi ? [fmt(tot[i])] : [])]);
  setTable(key, [S.gran === 'week' ? 'Week starting' : 'Month', ...ids.map(r => R[r].name), ...(multi ? ['All'] : [])], rows);
}

/* ---------- heat scale: one hue, five steps, thresholds from the data ---------- */
function heatScale(values) {
  const v = values.filter(x => x > 0).sort((a, b) => a - b);
  if (!v.length) return { level: () => 0, labels: [] };
  const q = p => v[Math.min(v.length - 1, Math.floor(p * v.length))];
  let th = [...new Set([q(.25), q(.5), q(.75)])].filter(x => x < v[v.length - 1]);
  const level = x => { if (x <= 0) return 0; let l = 1; for (const t of th) if (x > t) l++; return l + (4 - th.length - 1 >= 0 ? 4 - th.length - 1 : 0); };
  const bounds = [1, ...th.map(t => t + 1)], labels = bounds.map((lo, i) => { const hi = i < th.length ? th[i] : v[v.length - 1]; return lo === hi ? fmt(lo) : i === th.length ? fmt(lo) + '+' : fmt(lo) + '–' + fmt(hi); });
  return { level, labels, first: 4 - th.length };
}
const HEAT = [null, 24, 46, 72, 100];
const heatFill = l => l ? `color-mix(in oklab, var(--heat) ${HEAT[l]}%, var(--grid))` : 'var(--grid)';
function heatKey(scale, unit) {
  const el = h('div', { class: 'heatkey' }, h('i', { style: 'background:var(--grid)' }), h('span', { text: 'none' }));
  scale.labels.forEach((t, i) => el.append(h('i', { style: 'background:' + heatFill(scale.first + i) }), h('span', { text: t })));
  el.append(h('span', { text: unit }));
  return el;
}
function cellHover(svg, describe) {
  let cur = null;
  const clear = () => { if (cur) cur.removeAttribute('stroke'); cur = null; tip.hide(); };
  svg.addEventListener('pointermove', e => {
    const t = e.target.closest('[data-i]');
    if (!t) return clear();
    if (cur !== t) { if (cur) cur.removeAttribute('stroke'); cur = t; t.setAttribute('stroke', 'var(--ink)'); t.setAttribute('stroke-width', 1.5); }
    const d = describe(+t.dataset.i); tip.show(e.clientX, e.clientY, d.head, d.rows, d.total);
  });
  svg.addEventListener('pointerleave', clear);
}

function punchCard(S) {
  const P = panels.punch, W = Math.max(240, P.chart.clientWidth), L = 34, T = 18;
  const cw = (W - L) / 24, ch = Math.min(cw, 30), H = T + ch * 7 + 2;
  P.chart.replaceChildren();
  const svg = s('svg', { width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Commits by weekday and hour' }, P.chart);
  const scale = heatScale(S.punch.flat());
  for (let hr = 0; hr < 24; hr += 3) s('text', { x: L + hr * cw + 1, y: 11 }, svg, String(hr).padStart(2, '0'));
  for (let d = 0; d < 7; d++) {
    s('text', { x: 0, y: T + d * ch + ch / 2 + 4 }, svg, WD[d].slice(0, 3));
    for (let hr = 0; hr < 24; hr++)
      s('rect', { x: L + hr * cw + 1, y: T + d * ch + 1, width: cw - 2, height: ch - 2, rx: 3, 'data-i': d * 24 + hr, style: 'fill:' + heatFill(scale.level(S.punch[d][hr])) }, svg);
  }
  cellHover(svg, i => { const d = Math.floor(i / 24), hr = i % 24; return { head: `${WD[d]}, ${String(hr).padStart(2, '0')}:00 to ${String(hr + 1).padStart(2, '0')}:00`, rows: [{ n: 'Commits', v: fmt(S.punch[d][hr]) }] }; });
  P.chart.append(heatKey(scale, 'commits per hour slot'));
  setTable('punch', ['Weekday', ...Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'))], S.punch.map((row, d) => [WD[d], ...row.map(fmt)]));
}

function calendar(S) {
  const P = panels.cal, avail = Math.max(240, P.chart.clientWidth), L = 32, T = 18;
  const start = S.d0 - wdOf(S.d0), weeks = Math.floor((S.d1 - start) / 7) + 1;
  const cs = Math.max(13, Math.min(20, Math.floor((avail - L) / weeks))), W = L + weeks * cs, H = T + 7 * cs;
  const scale = heatScale([...S.days.values()].map(d => d.n));
  const scroller = h('div', { class: 'scroll' });
  P.chart.replaceChildren(scroller);
  const svg = s('svg', { width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Commits per day' }, scroller);
  [0, 2, 4, 6].forEach(d => s('text', { x: 0, y: T + d * cs + cs / 2 + 4 }, svg, WD[d].slice(0, 3)));
  let lastX = -99;
  for (let w = 0; w < weeks; w++) {
    const first = dObj(start + w * 7), x = L + w * cs;
    const monthStart = Array.from({ length: 7 }, (_, i) => dObj(start + w * 7 + i)).find(d => d.getUTCDate() === 1);
    const lab = w === 0 ? first : monthStart;
    if (lab) {
      const txt = MON[lab.getUTCMonth()] + (w === 0 || lab.getUTCMonth() === 0 ? ' ' + lab.getUTCFullYear() : ''), tw = txt.length * 6.6;
      if (x >= lastX + 5 && x + tw <= W) { s('text', { x, y: 11 }, svg, txt); lastX = x + tw; }
    }
    for (let d = 0; d < 7; d++) {
      const di = start + w * 7 + d; if (di < S.d0 || di > S.d1) continue;
      const v = S.days.get(di);
      s('rect', { x: x + 1.5, y: T + d * cs + 1.5, width: cs - 3, height: cs - 3, rx: 3, 'data-i': di, style: 'fill:' + heatFill(scale.level(v ? v.n : 0)) }, svg);
    }
  }
  cellHover(svg, di => {
    const v = S.days.get(di), head = WD[wdOf(di)].slice(0, 3) + ' ' + fmtDay(di);
    if (!v) return { head, rows: [{ n: 'No commits', v: '' }] };
    const rows = S.ids.filter(r => v.per[r]).map(r => ({ c: col(r), n: R[r].name, v: fmt(v.per[r]) }));
    return S.ids.length > 1 ? { head, rows, total: rows.length > 1 ? { n: 'All', v: fmt(v.n) } : null } : { head, rows: [{ n: 'Commits', v: fmt(v.n) }] };
  });
  P.chart.append(heatKey(scale, 'commits per day'));
  scroller.scrollLeft = scroller.scrollWidth;
  P.sub.textContent = `${plural(S.days.size, 'active day')} out of ${fmt(S.span)}. Each square is one day; a stronger colour means more commits.`;
  setTable('cal', ['Day', 'Commits', ...(S.ids.length > 1 ? S.ids.map(r => R[r].name) : [])],
    [...S.sorted].reverse().map(di => [WD[wdOf(di)].slice(0, 3) + ' ' + fmtDay(di), fmt(S.days.get(di).n), ...(S.ids.length > 1 ? S.ids.map(r => fmt(S.days.get(di).per[r] || 0)) : [])]));
}

/* ---------- languages ---------- */
function languages(S) {
  const P = panels.langs, KEEP = 10;
  let list = S.langs.filter(l => l.lines > 0);
  if (list.length > KEEP + 1) {
    const rest = list.slice(KEEP), other = { name: `${rest.length} more types`, lines: 0, files: 0, per: {} };
    rest.forEach(l => { other.lines += l.lines; other.files += l.files; for (const r in l.per) other.per[r] = (other.per[r] || 0) + l.per[r]; });
    list = [...list.slice(0, KEEP), other];
  }
  const max = Math.max(...list.map(l => l.lines), 1), grid = h('div', { class: 'bars' });
  for (const l of list) {
    const track = h('div', { class: 'track', tabindex: 0, style: `width:${Math.max(.6, l.lines / max * 100)}%`, 'aria-label': `${l.name}: ${fmt(l.lines)} lines` },
      S.ids.filter(r => l.per[r]).map(r => h('i', { style: `background:${col(r)};flex:${l.per[r]} 1 0` })));
    const show = (x, y) => tip.show(x, y, `${l.name}, ${plural(l.files, 'file')}`,
      S.ids.filter(r => l.per[r]).map(r => ({ c: col(r), n: R[r].name, v: fmt(l.per[r]) })), S.ids.length > 1 ? { n: 'All', v: fmt(l.lines) } : null);
    const row = h('div', { class: 'row' }, h('div', { class: 'name', text: l.name }), h('div', { style: 'min-width:0' }, track),
      h('div', { class: 'val' }, fmt(l.lines), h('small', { text: pct(l.lines / S.loc) })));
    for (const cell of row.children) { cell.addEventListener('pointermove', e => show(e.clientX, e.clientY)); cell.addEventListener('pointerleave', tip.hide); }
    track.addEventListener('focus', () => { const b = track.getBoundingClientRect(); show(b.left + 20, b.top); });
    track.addEventListener('blur', tip.hide);
    grid.append(row);
  }
  let exFiles = 0, exLines = 0; const items = [];
  for (const [why, [files, lines]] of S.excluded) { exFiles += files; exLines += lines; items.push(h('li', { text: `${why}: ${plural(files, 'file')}${lines ? ', ' + plural(lines, 'line') : ''}` })); }
  P.chart.replaceChildren(grid, h('details', null, h('summary', { text: `Not counted: ${plural(exFiles, 'file')} holding ${plural(exLines, 'text line')}` }), h('ul', null, items)));
  P.sub.textContent = `${fmt(S.loc)} lines in ${plural(S.counted, 'file')} at the current commit, by file type.`;
  setLegend('langs', S.ids);
}

/* ---------- people, records, largest commits, repo table ---------- */
function mini(per, ids, total, max, px) {
  return h('span', { class: 'minibox', style: `width:${px}px` }, h('span', { class: 'mini', style: `width:${Math.max(2, total / max * px)}px` },
    ids.filter(r => per[r]).map(r => h('i', { style: `background:${col(r)};flex:${per[r]} 1 0` }))));
}
function people(S) {
  const max = Math.max(...S.people.map(p => p.n));
  const rows = S.people.map(p => h('tr', null,
    h('td', null, h('span', { class: 'who' }, A[p.a].name, A[p.a].bot ? h('span', { class: 'tag', text: 'bot' }) : null)),
    h('td', null, mini(p.per, S.ids, p.n, max, 110), fmt(p.n), h('small', { text: pct(p.n / S.cs.length) })),
    h('td', { text: '+' + fmt(p.add) }), h('td', { text: '−' + fmt(p.del) }),
    h('td', { text: fmt(p.days.size) }), h('td', { text: fmtDay(day(p.first)) }), h('td', { text: fmtDay(day(p.last)) })));
  panels.people.chart.replaceChildren(h('table', null,
    h('thead', null, h('tr', null, ['Author', 'Commits', 'Lines added', 'Lines deleted', 'Active days', 'First commit', 'Latest commit'].map(t => h('th', { text: t, scope: 'col' })))),
    h('tbody', null, rows)));
  const humans = S.people.filter(p => !A[p.a].bot).length, bots = S.people.length - humans;
  panels.people.sub.textContent = `${plural(humans, 'person')}${bots ? ' and ' + plural(bots, 'bot') : ''}. Several names or addresses of one person count as one author.`
    .replace('persons', 'people');
}
function records(S) {
  const wd = S.punch.map(r => r.reduce((a, b) => a + b, 0)), hrs = Array.from({ length: 24 }, (_, i) => S.punch.reduce((a, r) => a + r[i], 0));
  const N = S.cs.length, bw = wd.indexOf(Math.max(...wd)), bh = hrs.indexOf(Math.max(...hrs));
  const wk = S.per[S.ids[0]].n.map((_, i) => S.ids.reduce((a, r) => a + S.per[r].n[i], 0)), bwk = wk.indexOf(Math.max(...wk));
  const sizes = S.cs.filter(c => !c.m).map(c => c.add + c.del).sort((a, b) => a - b), med = sizes.length ? sizes[Math.floor(sizes.length / 2)] : 0;
  const hh = x => String(x).padStart(2, '0') + ':00';
  const facts = [
    ['Busiest day', plural(S.busiest.n, 'commit'), fmtDay(S.busiest.d)],
    ['Longest streak', plural(S.best.len, 'day') + ' in a row', S.best.len > 1 ? `${fmtDay(S.best.end - S.best.len + 1, false)} to ${fmtDay(S.best.end)}` : fmtDay(S.best.end)],
    [S.gran === 'week' ? 'Busiest week' : 'Busiest month', plural(wk[bwk], 'commit'), S.bLabel(bwk)],
    ['Favourite weekday', WD[bw], pct(wd[bw] / N) + ' of commits'],
    ['Favourite hour', `${hh(bh)} to ${hh((bh + 1) % 24)}`, pct(hrs[bh] / N) + ' of commits'],
    ['Weekend share', pct((wd[5] + wd[6]) / N), 'committed on a Saturday or Sunday'],
    ['Pace', (N / S.days.size).toFixed(1) + ' commits', 'per active day'],
    ['Typical commit', plural(med, 'line') + ' changed', 'median, added plus deleted'],
  ];
  panels.records.chart.replaceChildren(h('dl', { class: 'facts' }, facts.map(f => [h('dt', { text: f[0] }), h('dd', null, f[1], h('small', { text: f[2] }))])));
}
function largest(S) {
  const all = [];
  for (const r of S.ids) for (const [j, sha, subject] of R[r].top) { const c = R[r].c[j]; if (c[2] + c[3] > 0) all.push({ r, sha, subject, t: c[0], a: c[1], add: c[2], del: c[3], f: c[4] }); }
  all.sort((p, q) => (q.add + q.del) - (p.add + p.del));
  panels.big.chart.replaceChildren(h('ol', { class: 'big' }, all.slice(0, 6).map(c => h('li', null,
    h('div', { class: 'subj', text: c.subject || '(no message)', title: c.subject }),
    h('div', { class: 'delta' }, h('b', { text: '+' + fmt(c.add) }), h('span', { text: '−' + fmt(c.del) })),
    h('div', { class: 'meta' }, dot(c.r), `${R[c.r].name}, ${fmtDay(day(c.t))}, ${A[c.a].name}, ${plural(c.f, 'file')}, ${c.sha}`)))));
}
function repoTable() {
  const maxLoc = Math.max(...R.map(r => r.loc));
  const rows = R.map((r, i) => {
    const humans = new Set(r.c.map(c => c[1]).filter(a => !A[a].bot)).size, per = {}; per[i] = 1;
    return h('tr', { class: sel === i ? 'sel' : null },
      h('td', null, h('button', { class: 'repo-btn', type: 'button', onclick: () => select(sel === i ? -1 : i), 'aria-pressed': sel === i }, dot(i), r.name)),
      h('td', { text: fmt(r.c.length) }),
      h('td', null, mini(per, [i], r.loc, maxLoc, 90), fmt(r.loc)),
      h('td', { text: fmt(r.files) }), h('td', { text: fmt(humans) }),
      h('td', { text: fmtDay(day(r.c[0][0])) }), h('td', { text: fmtDay(day(r.c[r.c.length - 1][0])) }),
      h('td', { class: 'l' }, r.langs.length ? r.langs[0][0] : '', r.langs.length ? h('small', { text: pct(r.langs[0][1] / r.loc) }) : null),
      h('td', { class: 'l', text: r.branch }));
  });
  panels.repos.chart.replaceChildren(h('table', null,
    h('thead', null, h('tr', null, ['Repository', 'Commits', 'Lines of code', 'Files', 'People', 'First commit', 'Latest commit', 'Top language', 'Branch'].map((t, i) => h('th', { text: t, scope: 'col', class: i > 6 ? 'l' : null })))),
    h('tbody', null, rows)));
}

function stats(S) {
  const humans = S.people.filter(p => !A[p.a].bot).length, bots = S.people.length - humans;
  const items = [
    [fmt(S.cs.length), 'Commits', S.merges ? `including ${plural(S.merges, 'merge')}` : 'no merges'],
    [fmt(S.loc), 'Lines of code today', `in ${plural(S.counted, 'counted file')}`],
    [fmt(S.files), 'Tracked files', `${fmt(S.files - S.counted)} of them not counted as code`],
    [fmt(humans), humans === 1 ? 'Contributor' : 'Contributors', bots ? `plus ${plural(bots, 'bot')}` : 'no bots'],
    ['+' + fmt(S.add), 'Lines added, all time', `${fmt(S.add / S.cs.length)} per commit on average`],
    ['−' + fmt(S.del), 'Lines deleted, all time', `${pct(S.del / (S.add || 1))} of what was added`],
    [fmt(S.days.size), 'Active days', `${pct(S.days.size / S.span)} of ${plural(S.span, 'day')}`],
    [fmtDay(S.d0), 'First commit', `latest on ${fmtDay(S.d1)}`],
  ];
  document.getElementById('stats').replaceChildren(...items.map(([v, l, sub]) => h('div', { class: 'stat' }, h('div', { class: 'v', text: v }), h('div', { class: 'l', text: l }), h('div', { class: 's', text: sub }))));
  const who = `${plural(humans, 'person')}`.replace('persons', 'people');
  document.getElementById('lede').textContent = sel < 0
    ? `${plural(R.length, 'repository').replace('repositorys', 'repositories')}, ${plural(S.cs.length, 'commit')} by ${who}, from ${fmtDay(S.d0)} to ${fmtDay(S.d1)}.`
    : `${R[sel].name} on branch ${R[sel].branch}: ${plural(S.cs.length, 'commit')} by ${who}, from ${fmtDay(S.d0)} to ${fmtDay(S.d1)}.`;
}

/* ---------- render ---------- */
function render() {
  tip.hide();
  const S = compute(sel), unit = S.gran === 'week' ? 'week' : 'month';
  document.documentElement.style.setProperty('--heat', col(sel));
  stats(S);
  panels.wave.sub.textContent = `Per ${unit}. Additions rise above the line, deletions fall below it.`;
  panels.commits.sub.textContent = `Per ${unit}${S.ids.length > 1 ? ', stacked by repository' : ''}.`;
  panels.growth.sub.textContent = `Running total of lines added minus lines deleted${S.ids.length > 1 ? ', stacked by repository' : ''}.`;
  barChart('wave', S, { up: p => p.add, down: p => p.del, height: 330, label: 'Lines added and deleted over time',
    val: (a, d) => `+${fmt(a)}  −${fmt(d)}`, empty: 'No changes' });
  barChart('commits', S, { up: p => p.n, label: 'Commits over time', val: a => fmt(a), empty: 'No commits' });
  growthChart('growth', S);
  languages(S);
  punchCard(S);
  calendar(S);
  people(S); records(S); largest(S); repoTable();
}
function select(i) {
  sel = i;
  document.querySelectorAll('#switch button').forEach((b, k) => b.setAttribute('aria-pressed', k - 1 === sel));
  try { history.replaceState(null, '', sel < 0 ? location.pathname + location.search : '#repo=' + encodeURIComponent(R[sel].name)); } catch (e) {}
  render();
}

const nav = document.getElementById('switch');
const total = R.reduce((a, r) => a + r.c.length, 0);
nav.append(h('button', { type: 'button', onclick: () => select(-1) }, h('i', { class: 'dot', style: 'background:var(--all)' }), 'All repositories', h('span', { class: 'n', text: fmt(total) })));
R.forEach((r, i) => nav.append(h('button', { type: 'button', onclick: () => select(i) }, dot(i), r.name, h('span', { class: 'n', text: fmt(r.c.length) }))));

/* theme: follows the system unless the reader picks one */
const themeBtn = document.getElementById('theme'), MODES = ['auto', 'light', 'dark'];
let mode = 'auto';
function setTheme(mo, save) {
  mode = mo;
  if (mo === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', mo);
  themeBtn.textContent = 'Theme: ' + mo;
  if (save) try { localStorage.setItem('commit-stats-theme', mo); } catch (e) {}
}
themeBtn.addEventListener('click', () => setTheme(MODES[(MODES.indexOf(mode) + 1) % 3], true));
const params = new URLSearchParams(location.hash.slice(1));
let saved = null; try { saved = localStorage.getItem('commit-stats-theme'); } catch (e) {}
setTheme(MODES.includes(params.get('theme')) ? params.get('theme') : MODES.includes(saved) ? saved : 'auto');

const foot = document.getElementById('foot');
const subs = [...new Set(R.flatMap(r => r.submodules))];
[
  `History is everything reachable from the checked-out commit of each repository (${R.map(r => `${r.name} at ${r.branch} ${r.head}`).join(', ')}). Commit counts include merge commits. Line changes come from git log --numstat with rename detection off; git prints no diff for a merge, so merged work is counted once, on the commits that made it. Binary changes are skipped. Conflict resolutions made inside a merge are therefore missing from the history charts, which is why the running total can end a few hundred lines away from today's count.`,
  `Lines of code are counted in the files of the current commit, read from git rather than the working folder. Left out of line counts, on both the current totals and the history: lockfiles, IDE and Xcode project metadata, generated or vendored files (Gradle wrapper, database schema snapshots, build output, minified files), SVG and binary files.${subs.length ? ` The ${subs.join(', ')} submodule is a link to another repository; its contents are not counted in any repository here.` : ''}`,
  `Times use each commit's author date in the author's own UTC offset. Identities are merged when they share an email address or a name, or when a GitHub no-reply address or a handle in brackets matches another name. Bots are listed but not counted as contributors.`,
  `Data as of the latest commit on ${fmtDay(day(Math.max(...R.map(r => r.c[r.c.length - 1][0]))))}.${D.skipped.length ? ' Skipped: ' + D.skipped.map(x => `${x[0]} (${x[1]})`).join(', ') + '.' : ''} Regenerate with python3 scripts/commit-stats.py.`,
].forEach(t => foot.append(h('p', { text: t })));

const want = params.get('repo'), idx = R.findIndex(r => r.name === want);
select(idx);
let lastW = document.documentElement.clientWidth, timer = 0;
window.addEventListener('resize', () => { clearTimeout(timer); timer = setTimeout(() => { const w = document.documentElement.clientWidth; if (w !== lastW) { lastW = w; render(); } }, 120); });
})();
</script>
</body>
</html>
'''


if __name__ == "__main__":
    sys.exit(main())
