#!/usr/bin/env python3
import argparse
import importlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(HERE))

from codescan import scan_code
from layout import blocks_layout, check_tools, columns_layout, flow_layout
from schema import find_base, parse_schema
from views import classes_view, database_view, packages_view

USAGE = """Generates docs/RELATION_MAP.html, a page of relation diagrams for the repository.
The curated views (system, credentials, API surface, pipelines, workers, events, startup,
build) are the modules under curated/, each returning nodes, links and the individual
flows behind every link, and are updated by hand when the code changes. The package and
class views are scanned from the Kotlin sources of :server, and the database view is
parsed from the B*__Base.sql under server/src/main/resources/db/migrations/postgres.
Needs Graphviz (dot and unflatten) on PATH."""

CURATED = ["system", "credentials", "api", "pipelines", "workers", "events", "startup", "build"]
VIEW_IDS = {"build": "modules"}
NODE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
FLAGS = {"unverified", "dead", "disabled-by-default"}
DASHES = {"", "dash", "dot"}
LABEL_LIMIT = 60


def parse_arguments():
    default_root = HERE.parent.parent
    parser = argparse.ArgumentParser(description=USAGE, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=default_root, help="repository root (default: derived from the script location)")
    return parser.parse_args()


def fail(view, message):
    sys.exit("curated view %s: %s" % (view, message))


def validate(name, view):
    for key in ("id", "tab", "noun", "blurb", "out", "inn", "layout", "groups", "styles", "nodes", "edges"):
        if key not in view:
            fail(name, "missing key %s" % key)
    kind = view["layout"].get("kind")
    if kind not in ("columns", "flow", "lanes"):
        fail(name, "layout kind must be columns, flow or lanes, got %r" % kind)
    if kind == "flow" and view["layout"].get("direction") not in ("LR", "TB"):
        fail(name, "flow layout needs direction LR or TB")
    groups = {}
    for g in view["groups"]:
        if "id" not in g or "label" not in g:
            fail(name, "group without id or label: %r" % g)
        if g["id"] in groups:
            fail(name, "duplicate group %s" % g["id"])
        if kind == "columns" and not isinstance(g.get("column"), int):
            fail(name, "group %s needs an integer column" % g["id"])
        groups[g["id"]] = g
    styles = set()
    if len(view["styles"]) > 3:
        fail(name, "at most 3 styles")
    for s in view["styles"]:
        if s.get("dash") not in DASHES:
            fail(name, "style %r has an unknown dash" % s)
        styles.add(s["kind"])
    nodes = set()
    for n in view["nodes"]:
        for key in ("id", "label", "group"):
            if not n.get(key):
                fail(name, "node without %s: %r" % (key, n))
        if not NODE_ID.match(n["id"]):
            fail(name, "node id %r has characters outside [A-Za-z0-9._-]" % n["id"])
        if n["id"] in nodes:
            fail(name, "duplicate node %s" % n["id"])
        if n["group"] not in groups:
            fail(name, "node %s is in unknown group %s" % (n["id"], n["group"]))
        if set(n.get("flags", [])) - FLAGS:
            fail(name, "node %s has unknown flags %r" % (n["id"], n["flags"]))
        nodes.add(n["id"])
    seen = set()
    for e in view["edges"]:
        where = "edge %s -> %s" % (e.get("from"), e.get("to"))
        if e.get("from") not in nodes or e.get("to") not in nodes:
            fail(name, "%s references an unknown node" % where)
        if e["from"] == e["to"]:
            fail(name, "%s is a self loop" % where)
        if e.get("kind") not in styles:
            fail(name, "%s has kind %r, not one of the view styles" % (where, e.get("kind")))
        key = (e["from"], e["to"], e["kind"])
        if key in seen:
            fail(name, "%s (%s) appears twice" % (where, e["kind"]))
        seen.add(key)
        if not e.get("flows"):
            fail(name, "%s has no flows" % where)
        for flow in e["flows"]:
            for field in ("title", "transport", "carries", "src"):
                if not flow.get(field):
                    fail(name, "%s has a flow without %s: %r" % (where, field, flow.get("title")))
    for note in view.get("notes", []):
        if not note.get("title") or not note.get("text"):
            fail(name, "note without title or text: %r" % note)


def curated_view(name, root):
    if not (HERE / "curated" / (name + ".py")).exists():
        print("warning: curated/%s.py does not exist, view skipped" % name)
        return None
    module = importlib.import_module("curated." + name)
    view = module.build(root)
    validate(name, view)
    labels = len(view["edges"]) < LABEL_LIMIT
    groups = [g for g in view["groups"] if any(n["group"] == g["id"] for n in view["nodes"])]
    if view["layout"]["kind"] == "columns":
        result = columns_layout(view["nodes"], view["edges"], groups, labels, bool(view["layout"].get("ordered")), bool(view["layout"].get("bundle")))
    elif view["layout"]["kind"] == "lanes":
        result = blocks_layout(view["nodes"], view["edges"], groups, lanes=True)
    else:
        result = flow_layout(view["nodes"], view["edges"], groups, view["layout"]["direction"], labels)
    out = {key: view[key] for key in ("tab", "noun", "blurb", "out", "inn", "styles")}
    out["id"] = VIEW_IDS.get(name, view["id"])
    out["groups"] = [{key: g[key] for key in ("id", "label", "desc") if key in g} for g in groups]
    out["notes"] = view.get("notes", [])
    out["labels"] = labels
    out.update(result)
    return out


def main():
    arguments = parse_arguments()
    root = arguments.root.resolve()
    check_tools()
    base = find_base(root)
    schema = parse_schema(base)
    code = scan_code(root)

    views = []
    for name in CURATED:
        view = curated_view(name, root)
        if view is not None:
            views.append(view)
    views.append(packages_view(code))
    views.append(classes_view(code))
    views.append(database_view(schema, base.name.split("__")[0]))
    for view in views:
        print("%-11s %4d nodes %4d edges %6d x %-6d" % (view["id"], len(view["nodes"]), len(view["edges"]), view["w"], view["h"]))

    data = json.dumps(views, separators=(",", ":")).replace("</", "<\\/")
    template = (HERE / "template.html").read_text()
    page = template.replace("/*__DATA__*/null", data)
    output = root / "docs" / "RELATION_MAP.html"
    output.write_text(page)
    print("wrote %s (%d bytes)" % (output.relative_to(root), len(page.encode())))


if __name__ == "__main__":
    main()
